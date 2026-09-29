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
  "version": 1,
  "paths": {
    "config.quick-look": {
      "label": "Quick Look",
      "desc": "",
      "type": "string",
      "default": true
    },
    "config.iflow.engine.parallel": {
      "label": "IFlow Engine Parallel",
      "desc": "",
      "type": "boolean",
      "default": false
    },
    "config.sonido.enabled": {
      "label": "Sonido Enabled",
      "desc": "",
      "type": "boolean",
      "default": true
    },
    "config.general.language": {
      "label": "General Language",
      "desc": "",
      "type": "string",
      "default": "en"
    },
    "config.chat.claim-mode": {
      "label": "Chat Claim Mode",
      "desc": "",
      "type": "integer",
      "default": "cancel-event"
    },
    "config.chat.log-to-console": {
      "label": "Chat Log To Console",
      "desc": "",
      "type": "boolean",
      "default": true
    },
    "config.repositories[].name": {
      "label": "Repository Name",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "config.repositories[].url": {
      "label": "Repository URL",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "config.repositories[].type": {
      "label": "Repository Type",
      "desc": "",
      "type": "string",
      "default": "chat"
    },
    "config.repositories[].enabled": {
      "label": "Repository Enabled",
      "desc": "",
      "type": "boolean",
      "default": true
    },
    "channels[].name": {
      "label": "Channel Name",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "channels[].permission": {
      "label": "Channel Permission",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "channels[].send-permission": {
      "label": "Channel Send Permission",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "channels[].receive-permission": {
      "label": "Channel Receive Permission",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "channels[].messages": {
      "label": "Channel Messages",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "channels[].tooltips": {
      "label": "Channel Tooltips",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "channels[].show-sender": {
      "label": "Channel Show Sender",
      "desc": "",
      "type": "boolean",
      "default": true
    },
    "channels[].rate-limit-per-second": {
      "label": "Channel Rate Limit",
      "desc": "",
      "type": "integer",
      "default": 0
    },
    "channels[].lang-source": {
      "label": "Channel Lang Source",
      "desc": "",
      "type": "string",
      "default": "auto"
    },
    "channels[].lang-target": {
      "label": "Channel Lang Target",
      "desc": "",
      "type": "string",
      "default": "auto"
    },
    "channels[].type": {
      "label": "Channel Type",
      "desc": "",
      "type": "string",
      "default": "chat"
    },
    "channels[].sounds": {
      "label": "Channel Sounds",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "channels[].sounds[].name": {
      "label": "Sound Name",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "channels[].sounds[].volume": {
      "label": "Sound Volume",
      "desc": "",
      "type": "float",
      "default": 1.0
    },
    "channels[].sounds[].pitch": {
      "label": "Sound Pitch",
      "desc": "",
      "type": "float",
      "default": 1.0
    },
    "translators.google.active": {
      "label": "Activo (Google)",
      "desc": "",
      "type": "boolean",
      "default": true
    },
    "translators.google.provider": {
      "label": "Proveedor (Google)",
      "desc": "",
      "type": "string",
      "default": "google"
    },
    "translators.libre.active": {
      "label": "Activo (Libre)",
      "desc": "",
      "type": "boolean",
      "default": false
    },
    "translators.libre.provider": {
      "label": "Proveedor (Libre)",
      "desc": "",
      "type": "string",
      "default": "libre"
    },
    "translators.libre.base-url": {
      "label": "Base URL (Libre)",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "translators.libre.api-key": {
      "label": "API Key (Libre)",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "translators.libre.pool.max-concurrent": {
      "label": "Max Concurrent (Libre)",
      "desc": "",
      "type": "integer",
      "default": 6
    },
    "sync.discord.enabled": {
      "label": "Habilitado (Discord)",
      "desc": "",
      "type": "boolean",
      "default": false
    },
    "sync.discord.token": {
      "label": "Token (Discord)",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "sync.discord.channel": {
      "label": "Channel ID (Discord)",
      "desc": "",
      "type": "integer",
      "default": 0
    },
    "sync.discord.intents": {
      "label": "Intents (Discord)",
      "desc": "",
      "type": "array",
      "default": [
        "GUILD_MESSAGES",
        "MESSAGE_CONTENT"
      ]
    },
    "sync.telegram.enabled": {
      "label": "Habilitado (Telegram)",
      "desc": "",
      "type": "boolean",
      "default": false
    },
    "sync.telegram.token": {
      "label": "Token (Telegram)",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "sync.telegram.chat-id": {
      "label": "Chat ID (Telegram)",
      "desc": "",
      "type": "integer",
      "default": 0
    },
    "sync.telegram.hub": {
      "label": "Hub Channel (Telegram)",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "sync.http.enabled": {
      "label": "Habilitado (HTTP)",
      "desc": "",
      "type": "boolean",
      "default": false
    },
    "sync.http.webhook-url": {
      "label": "Webhook URL (HTTP)",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "sync.http.inbound-port": {
      "label": "Inbound Port (HTTP)",
      "desc": "",
      "type": "integer",
      "default": 0
    },
    "sync.http.path": {
      "label": "Path (HTTP)",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "sync.tcp-udp.enabled": {
      "label": "Habilitado (TCP/UDP)",
      "desc": "",
      "type": "boolean",
      "default": false
    },
    "sync.tcp-udp.protocol": {
      "label": "Protocol (TCP/UDP)",
      "desc": "",
      "type": "string",
      "default": "TCP"
    },
    "sync.tcp-udp.host": {
      "label": "Host (TCP/UDP)",
      "desc": "",
      "type": "string",
      "default": "0.0.0.0"
    },
    "sync.tcp-udp.outbound-port": {
      "label": "Outbound Port (TCP/UDP)",
      "desc": "",
      "type": "integer",
      "default": 0
    },
    "sync.tcp-udp.inbound-port": {
      "label": "Inbound Port (TCP/UDP)",
      "desc": "",
      "type": "integer",
      "default": 0
    },
    "sync.velocity.enabled": {
      "label": "Habilitado (Velocity)",
      "desc": "Proxy plugin (F7+)",
      "type": "boolean",
      "default": false
    },
    "sync.velocity.secret": {
      "label": "Secret (Velocity)",
      "desc": "",
      "type": "string",
      "default": ""
    },
    "sync.velocity.servers": {
      "label": "Servers (Velocity)",
      "desc": "",
      "type": "array",
      "default": []
    },
    "sync.velocity.mapping": {
      "label": "Mapping (Velocity)",
      "desc": "",
      "type": "string",
      "default": "* → chat.hub"
    },
    "graph.guard.max-steps": {
      "label": "Max Steps",
      "desc": "Límite de pasos en el grafo iFlow",
      "type": "integer",
      "default": 512
    },
    "graph.filter.dedup-fanout": {
      "label": "Dedup Fan-out",
      "desc": "Deduplicación de fan-out",
      "type": "boolean",
      "default": true
    },
    "graph.filter.priority": {
      "label": "Priority Strategy",
      "desc": "Estrategia de prioridad",
      "type": "string",
      "default": "batch-first"
    }
  }
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
