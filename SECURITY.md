# Security Policy

## Estado del proyecto

TextFormatter Suite está **en desarrollo y no es apto para producción**. No hay
releases públicos, tags estables ni soporte de versiones anteriores. La rama
`main` es la única referencia y puede cambiar sin aviso.

## Cómo reportar una vulnerabilidad

**No abras un issue público para un problema de seguridad.**

Usa el reporte privado de vulnerabilidades de GitHub:

1. Entra en <https://github.com/majhrs16-official/TextFormatter-Suite/security/advisories/new>.
2. Describe el problema, el módulo afectado, el commit y los pasos mínimos para reproducirlo.
3. Indica el impacto esperado y, si puedes, una propuesta de corrección.

Si el reporte privado no está disponible, contacta al mantenedor
([@majhrs16-official](https://github.com/majhrs16-official)) por GitHub sin
publicar detalles explotables.

## Alcance

Se consideran relevantes, entre otros:

- deserialización de YAML/JSON y configuración de extensiones;
- validación de URLs, SSRF, redirecciones y descarga de paquetes;
- gestión de secretos, tokens y comparaciones de credenciales;
- path traversal, ZIP bombs y límites de recursos en el Module Manager;
- aislamiento de la VM iFlow, MMU, MMIO y servicios `HOST`;
- XSS o ejecución de código en el editor web estático.

Quedan fuera del alcance las vulnerabilidades de dependencias de terceros
(reportarlas al proyecto upstream), los servidores Minecraft mal configurados y
los ataques de ingeniería social.

## Respuesta

La revisión es **best effort** y no hay SLA ni equipo de seguridad dedicado. Se
intentará confirmar el reporte, preparar una corrección y coordinarla contigo
antes de hacerla pública. No se promete una fecha de parche ni un backport,
porque no existe una versión estable que mantener.

## Divulgación

Se prefiere la divulgación coordinada. Si quieres crédito en la corrección,
indícalo en el reporte; si prefieres permanecer en el anonimato, también es
posible.
