package io.github.squysh.systemeeveil

import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Passerelle vers Health Connect, le carrefour de donnees sante d'Android.
 *
 * Garmin Connect y depose pas, sommeil, frequence cardiaque et seances ;
 * une balance connectee y depose poids et masse grasse. Ce module lit ces
 * enregistrements et les rend au format que l'application comprend deja.
 *
 * Aucune donnee ne quitte l'appareil : tout transite par le pont Capacitor
 * vers la page web embarquee.
 */
@CapacitorPlugin(name = "SanteConnect")
class SanteConnect : Plugin() {

    private val permissions = setOf(
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(StepsRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(SleepSessionRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(HeartRateRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(WeightRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(BodyFatRecord::class)
    )

    private fun client(): HealthConnectClient? = try {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE)
            HealthConnectClient.getOrCreate(context) else null
    } catch (e: Throwable) {
        null
    }

    /** Etat du carrefour et des autorisations, sans rien demander. */
    @PluginMethod
    fun status(call: PluginCall) {
        val res = JSObject()
        val sdk = try {
            HealthConnectClient.getSdkStatus(context)
        } catch (e: Throwable) {
            res.put("erreur", e.message ?: e.toString())
            -1
        }
        res.put("sdk", when (sdk) {
            HealthConnectClient.SDK_AVAILABLE -> "disponible"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "mise_a_jour_requise"
            HealthConnectClient.SDK_UNAVAILABLE -> "indisponible"
            else -> "inconnu"
        })
        res.put("androidSdk", android.os.Build.VERSION.SDK_INT)
        val c = client()
        if (c == null) {
            res.put("accordees", JSArray())
            res.put("total", permissions.size)
            call.resolve(res)
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ok = c.permissionController.getGrantedPermissions()
                val arr = JSArray()
                ok.forEach { arr.put(it.substringAfterLast('.')) }
                res.put("accordees", arr)
                res.put("total", permissions.size)
                res.put("completes", ok.containsAll(permissions))
            } catch (e: Throwable) {
                res.put("erreur", e.message ?: e.toString())
            }
            withContext(Dispatchers.Main) { call.resolve(res) }
        }
    }

    /** Ouvre l'ecran systeme d'autorisation de Health Connect. */
    @PluginMethod
    fun demander(call: PluginCall) {
        val c = client()
        if (c == null) {
            call.reject("Health Connect indisponible sur cet appareil")
            return
        }
        try {
            val contract = PermissionController.createRequestPermissionResultContract()
            val intent = contract.createIntent(context, permissions)
            startActivityForResult(call, intent, "retourAutorisation")
        } catch (e: Throwable) {
            call.reject(e.message ?: "Impossible d ouvrir l ecran d autorisation")
        }
    }

    @ActivityCallback
    private fun retourAutorisation(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        status(call)
    }

    /**
     * Lit les enregistrements des derniers jours et les renvoie regroupes
     * par journee, prets a alimenter le suivi.
     */
    @PluginMethod
    fun lire(call: PluginCall) {
        val jours = call.getInt("jours") ?: 7
        val c = client()
        if (c == null) {
            call.reject("Health Connect indisponible sur cet appareil")
            return
        }
        val fin = Instant.now()
        val debut = fin.minus(jours.toLong(), ChronoUnit.DAYS)
        val zone = ZoneId.systemDefault()
        fun jour(i: Instant): String = i.atZone(zone).toLocalDate().toString()

        CoroutineScope(Dispatchers.IO).launch {
            val res = JSObject()
            try {
                val filtre = TimeRangeFilter.between(debut, fin)

                // Seances : distance, duree, calories, frequence cardiaque
                val seances = JSArray()
                c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, filtre)).records.forEach { s ->
                    val o = JSObject()
                    o.put("date", jour(s.startTime))
                    o.put("titre", s.title ?: "")
                    o.put("type", s.exerciseType)
                    o.put("secondes", java.time.Duration.between(s.startTime, s.endTime).seconds)
                    seances.put(o)
                }
                res.put("seances", seances)

                // Distance parcourue, agregee par journee
                val dist = JSObject()
                c.readRecords(ReadRecordsRequest(
                    androidx.health.connect.client.records.DistanceRecord::class, filtre)).records.forEach { d ->
                    val k = jour(d.startTime)
                    dist.put(k, (dist.optDouble(k, 0.0)) + d.distance.inMeters)
                }
                res.put("distance", dist)

                // Sommeil, en heures par journee de reveil
                val sommeil = JSObject()
                c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, filtre)).records.forEach { s ->
                    val k = jour(s.endTime)
                    val h = java.time.Duration.between(s.startTime, s.endTime).seconds / 3600.0
                    sommeil.put(k, (sommeil.optDouble(k, 0.0)) + h)
                }
                res.put("sommeil", sommeil)

                // Pas
                val pas = JSObject()
                c.readRecords(ReadRecordsRequest(StepsRecord::class, filtre)).records.forEach { p ->
                    val k = jour(p.startTime)
                    pas.put(k, (pas.optDouble(k, 0.0)) + p.count)
                }
                res.put("pas", pas)

                // Depense energetique
                val kcal = JSObject()
                c.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, filtre)).records.forEach { e ->
                    val k = jour(e.startTime)
                    kcal.put(k, (kcal.optDouble(k, 0.0)) + e.energy.inKilocalories)
                }
                res.put("kcal", kcal)

                // Poids et masse grasse
                val poids = JSObject()
                c.readRecords(ReadRecordsRequest(WeightRecord::class, filtre)).records.forEach { w ->
                    poids.put(jour(w.time), w.weight.inKilograms)
                }
                res.put("poids", poids)
                val mg = JSObject()
                c.readRecords(ReadRecordsRequest(BodyFatRecord::class, filtre)).records.forEach { b ->
                    mg.put(jour(b.time), b.percentage.value)
                }
                res.put("masseGrasse", mg)

                res.put("ok", true)
            } catch (e: Throwable) {
                res.put("ok", false)
                res.put("erreur", e.message ?: e.toString())
            }
            withContext(Dispatchers.Main) { call.resolve(res) }
        }
    }
}
