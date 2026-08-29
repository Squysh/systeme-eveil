package io.github.squysh.systemeeveil

import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
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
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(DistanceRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(WeightRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(BodyFatRecord::class)
    )

    /** Traduit le type Health Connect vers le vocabulaire de l application. */
    private fun typeLisible(t: Int): String = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "endurance"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "trail"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "marche"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "velo"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING,
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "souplesse"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "renfort"
        else -> "autre"
    }

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
            // Chaque famille de donnees est lue isolement : une autorisation
            // manquante ou un enregistrement illisible ne doit pas emporter
            // tout le reste de la releve avec lui.
            val soucis = JSArray()
            fun noter(quoi: String, e: Throwable) {
                soucis.put(quoi + " : " + (e.message ?: e.toString()))
            }
            val filtre = TimeRangeFilter.between(debut, fin)

            // Chaque seance porte sa propre distance, sa depense et sa FC :
            // un agregat par journee ne permettrait pas de les rattacher.
            val seances = JSArray()
            try {
                // Plusieurs passerelles decrivent la meme seance : Health Sync et
                // Strava deposent chacun la sienne. Sans dedoublonnage, distances et
                // calories sont comptees deux fois. On garde un exemplaire par minute
                // de depart, en preferant celui qui porte un titre.
                val vues = mutableListOf<Pair<Long, Long>>()
                c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, filtre)).records
                    .sortedByDescending { (it.title ?: "").length }
                    .filter { s ->
                        val d = s.startTime.epochSecond
                        val f = s.endTime.epochSecond
                        val duree = (f - d).coerceAtLeast(1)
                        // Deux enregistrements qui se recouvrent largement decrivent
                        // la meme seance, meme si leurs bornes different de plusieurs
                        // minutes : Health Sync et Strava ne la decoupent pas pareil.
                        val double = vues.any { (a, b) ->
                            val commun = minOf(f, b) - maxOf(d, a)
                            commun > 0 && commun * 2 > duree
                        }
                        if (!double) vues.add(d to f)
                        !double
                    }
                    .forEach { s ->
                    val plage = TimeRangeFilter.between(s.startTime, s.endTime)
                    val o = JSObject()
                    o.put("date", jour(s.startTime))
                    o.put("titre", s.title ?: "")
                    o.put("type", typeLisible(s.exerciseType))
                    o.put("secondes", java.time.Duration.between(s.startTime, s.endTime).seconds)
                    try {
                        o.put("metres", c.readRecords(ReadRecordsRequest(DistanceRecord::class, plage))
                            .records.groupBy { it.metadata.dataOrigin.packageName }
                            .map { (_, l) -> l.sumOf { r -> r.distance.inMeters } }
                            .maxOrNull() ?: 0.0)
                    } catch (e: Throwable) { noter("distance", e) }
                    try {
                        o.put("kcal", c.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, plage))
                            .records.groupBy { it.metadata.dataOrigin.packageName }
                            .map { (_, l) -> l.sumOf { r -> r.energy.inKilocalories } }
                            .maxOrNull() ?: 0.0)
                    } catch (e: Throwable) { noter("depense", e) }
                    // Les repetitions d'une seance de force vivent dans ses segments,
                    // mais cette version de la bibliotheque n'expose pas les constantes
                    // qui distinguent une pompe d'un squat. On releve le total, faute
                    // de pouvoir le repartir : deviner les identifiants numeriques
                    // ferait entrer des valeurs fausses dans le suivi.
                    val reps = s.segments.sumOf { it.repetitions }
                    if (reps > 0) o.put("repetitions", reps)
                    try {
                        val bpm = c.readRecords(ReadRecordsRequest(HeartRateRecord::class, plage))
                            .records.flatMap { it.samples }.map { it.beatsPerMinute }
                        if (bpm.isNotEmpty()) o.put("fc", bpm.average())
                    } catch (e: Throwable) { noter("frequence cardiaque", e) }
                    seances.put(o)
                }
            } catch (e: Throwable) { noter("seances", e) }
            res.put("seances", seances)

            // Sommeil, en heures par journee de reveil
            val sommeil = JSObject()
            val profond = JSObject()
            val paradoxal = JSObject()
            // Additionner les nuits comptait deux fois ce que deux applications
            // decrivent au meme moment. On retient l'union des intervalles : un
            // meme sommeil rapporte par deux sources ne compte qu'une fois, une
            // sieste separee compte en plus, et une nuit decoupee se recolle.
            val plages = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
            try {
                c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, filtre)).records.forEach { s ->
                    val k = jour(s.endTime)
                    val liste = plages.getOrPut(k) { mutableListOf() }
                    // La session va du coucher au lever : elle contient les reveils
                    // nocturnes. Garmin, lui, annonce le sommeil reel. Quand les
                    // phases sont connues, on ne retient donc que celles ou l'on
                    // dort vraiment ; sinon on retombe sur la session entiere.
                    val endormi = s.stages.filter {
                        it.stage == SleepSessionRecord.STAGE_TYPE_SLEEPING ||
                        it.stage == SleepSessionRecord.STAGE_TYPE_LIGHT ||
                        it.stage == SleepSessionRecord.STAGE_TYPE_DEEP ||
                        it.stage == SleepSessionRecord.STAGE_TYPE_REM
                    }
                    if (endormi.isEmpty()) {
                        liste.add(s.startTime.epochSecond to s.endTime.epochSecond)
                    } else {
                        endormi.forEach { liste.add(it.startTime.epochSecond to it.endTime.epochSecond) }
                    }
                    var pf = 0L; var rem = 0L
                    s.stages.forEach { st ->
                        val d2 = java.time.Duration.between(st.startTime, st.endTime).seconds
                        if (st.stage == SleepSessionRecord.STAGE_TYPE_DEEP) pf += d2
                        if (st.stage == SleepSessionRecord.STAGE_TYPE_REM) rem += d2
                    }
                    // Meme raison pour les stades : on garde le releve le plus
                    // fourni plutot que d'empiler ceux qui se repetent.
                    if (pf > 0) profond.put(k, maxOf(profond.optDouble(k, 0.0), pf / 3600.0))
                    if (rem > 0) paradoxal.put(k, maxOf(paradoxal.optDouble(k, 0.0), rem / 3600.0))
                }
                plages.forEach { (k, l) ->
                    var total = 0L
                    var debut = -1L
                    var fin = -1L
                    l.sortedBy { it.first }.forEach { (d, f) ->
                        when {
                            debut < 0 -> { debut = d; fin = f }
                            d <= fin -> fin = maxOf(fin, f)
                            else -> { total += fin - debut; debut = d; fin = f }
                        }
                    }
                    if (debut >= 0) total += fin - debut
                    sommeil.put(k, total / 3600.0)
                }
            } catch (e: Throwable) { noter("sommeil", e) }
            res.put("sommeil", sommeil)
            res.put("profond", profond)
            res.put("paradoxal", paradoxal)

            // Pas
            val pas = JSObject()
            try {
                c.readRecords(ReadRecordsRequest(StepsRecord::class, filtre)).records
                    .groupBy { jour(it.startTime) }
                    .forEach { (k, l) ->
                        pas.put(k, l.groupBy { it.metadata.dataOrigin.packageName }
                            .map { (_, m) -> m.sumOf { r -> r.count } }
                            .maxOrNull()?.toDouble() ?: 0.0)
                    }
            } catch (e: Throwable) { noter("pas", e) }
            res.put("pas", pas)

            // Depense energetique
            val kcal = JSObject()
            try {
                c.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, filtre)).records
                    .groupBy { jour(it.startTime) }
                    .forEach { (k, l) ->
                        kcal.put(k, l.groupBy { it.metadata.dataOrigin.packageName }
                            .map { (_, m) -> m.sumOf { r -> r.energy.inKilocalories } }
                            .maxOrNull() ?: 0.0)
                    }
            } catch (e: Throwable) { noter("depense journaliere", e) }
            res.put("kcal", kcal)

            // Frequence cardiaque de repos et variabilite : les deux marqueurs
            // qui disent comment le corps encaisse la charge.
            val fcRepos = JSObject()
            try {
                c.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, filtre)).records.forEach { r ->
                    fcRepos.put(jour(r.time), r.beatsPerMinute.toDouble())
                }
            } catch (e: Throwable) { noter("FC de repos", e) }
            res.put("fcRepos", fcRepos)

            val vfc = JSObject()
            try {
                c.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, filtre)).records.forEach { r ->
                    vfc.put(jour(r.time), r.heartRateVariabilityMillis)
                }
            } catch (e: Throwable) { noter("variabilite cardiaque", e) }
            res.put("vfc", vfc)

            // Poids et masse grasse
            val poids = JSObject()
            try {
                c.readRecords(ReadRecordsRequest(WeightRecord::class, filtre)).records.forEach { w ->
                    poids.put(jour(w.time), w.weight.inKilograms)
                }
            } catch (e: Throwable) { noter("poids", e) }
            res.put("poids", poids)

            val mg = JSObject()
            try {
                c.readRecords(ReadRecordsRequest(BodyFatRecord::class, filtre)).records.forEach { b ->
                    mg.put(jour(b.time), b.percentage.value)
                }
            } catch (e: Throwable) { noter("masse grasse", e) }
            res.put("masseGrasse", mg)

            // Une releve partielle reste exploitable : on la rend, en disant
            // ce qui a manque plutot que de tout refuser.
            res.put("ok", true)
            if (soucis.length() > 0) res.put("soucis", soucis)
            withContext(Dispatchers.Main) { call.resolve(res) }
        }
    }
}
