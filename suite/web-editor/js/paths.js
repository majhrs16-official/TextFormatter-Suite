/* paths.js — carga y gestiona paths.json (fuente única de verdad para data-bind) */
(function (global) {
  'use strict';

  let pathsData = null;
  let pathsLoaded = false;
  let loadPromise = null;

  async function loadPaths() {
    if (pathsLoaded) {
      return pathsData;
    }
    if (loadPromise) {
      return loadPromise;
    }

    loadPromise = (async () => {
      try {
        const res = await fetch('./paths.json');
        if (!res.ok) {
          throw new Error('Failed to load paths.json: ' + res.status);
        }
        pathsData = await res.json();
        pathsLoaded = true;
        return pathsData;
      } catch (e) {
        console.warn('Failed to load paths.json, using fallback:', e);
        pathsData = getFallbackPaths();
        pathsLoaded = true;
        return pathsData;
      }
    })();
    return loadPromise;
  }

  function getFallbackPaths() {
    return {
      version: 1,
      paths: {
        'config.quick-look': {
          label: 'Quick-look',
          desc: 'Ventana temporal del mensaje',
          type: 'boolean',
          default: true,
        },
        'config.sonido.enabled': {
          label: 'Sonido habilitado',
          desc: 'Notificaciones del plugin',
          type: 'boolean',
          default: true,
        },
        'config.iflow.engine.parallel': {
          label: 'engine.parallel',
          desc: 'Ruta dirigida en paralelo por canal',
          type: 'boolean',
          default: false,
        },
        'graph.filter.dedup-fanout': {
          label: 'Dedup fan-out',
          desc: 'Mensaje, camino→destino',
          type: 'boolean',
          default: true,
        },
        'translators.google.active': { label: 'Activo (Google)', desc: '', type: 'boolean', default: true },
        'translators.libre.active': { label: 'Activo (Libre)', desc: '', type: 'boolean', default: false },
        'sync.discord.enabled': { label: 'Habilitado (Discord)', desc: '', type: 'boolean', default: false },
        'sync.telegram.enabled': { label: 'Habilitado (Telegram)', desc: '', type: 'boolean', default: false },
        'sync.http.enabled': { label: 'Habilitado (HTTP)', desc: '', type: 'boolean', default: false },
        'sync.tcp-udp.enabled': { label: 'Habilitado (TCP/UDP)', desc: '', type: 'boolean', default: false },
        'sync.velocity.enabled': {
          label: 'Habilitado (Velocity)',
          desc: 'Proxy plugin (F7+)',
          type: 'boolean',
          default: false,
        },
      },
    };
  }

  function getPaths() {
    if (!pathsLoaded) {
      console.warn('paths.json not loaded yet, using fallback');
      return getFallbackPaths().paths;
    }
    return pathsData.paths;
  }

  function getPathMeta(path) {
    const paths = getPaths();
    return paths[path];
  }

  function getAllSwitchPaths() {
    const paths = getPaths();
    return Object.entries(paths)
      .filter(([_, meta]) => meta.type === 'boolean')
      .map(([path, meta]) => ({ path, ...meta }));
  }

  global.Suite = global.Suite || {};
  global.Suite.paths = {
    load: loadPaths,
    getPaths: getPaths,
    getPathMeta: getPathMeta,
    getAllSwitchPaths: getAllSwitchPaths,
    getFallbackPaths: getFallbackPaths,
  };
})(window || this);
