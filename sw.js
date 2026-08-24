/* Réseau d'abord : une mise à jour est toujours prise en compte dès qu'il y a
   du réseau, et le cache ne sert que de secours hors ligne. C'est l'inverse du
   comportement par défaut de Safari, qui gardait indéfiniment l'ancienne page. */
const CACHE = "systeme-eveil";

self.addEventListener("install", e => self.skipWaiting());

self.addEventListener("activate", e => e.waitUntil(
  caches.keys()
    .then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim())
));

self.addEventListener("fetch", e => {
  const r = e.request;
  if (r.method !== "GET" || new URL(r.url).origin !== self.location.origin) return;
  e.respondWith(
    fetch(r)
      .then(rep => {
        if (rep && rep.ok) {
          const copie = rep.clone();
          caches.open(CACHE).then(c => c.put(r, copie));
        }
        return rep;
      })
      .catch(() => caches.match(r).then(c => c || caches.match("./")))
  );
});
