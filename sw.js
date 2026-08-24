/* Réseau d'abord, avec précache complet.

   Réseau d'abord : une mise à jour est prise en compte dès qu'il y a du réseau,
   même depuis un raccourci d'écran d'accueil, là où Safari gardait sinon
   l'ancienne page indéfiniment.

   Précache : tout ce dont l'application a besoin est enregistré dès
   l'installation, pour qu'elle démarre en mode avion, dès la première fois. */
const CACHE = "systeme-eveil-v3";

const RESSOURCES = [
  "./",
  "./index.html",
  "./fonts.css",
  "./manifest.json",
  "./icon-180.png",
  "./icon-192.png",
  "./icon-512.png",
  "./fonts/ChakraPetch-400.woff2",
  "./fonts/ChakraPetch-600.woff2",
  "./fonts/ChakraPetch-700.woff2",
  "./fonts/IBMPlexMono-400.woff2",
  "./fonts/IBMPlexMono-600.woff2",
  "./fonts/IBMPlexSans-var.woff2",
];

self.addEventListener("install", e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(RESSOURCES))
      .then(() => self.skipWaiting())
  );
});

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
      .catch(() => caches.match(r).then(c => c || caches.match("./index.html")))
  );
});
