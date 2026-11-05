# iflow-firewall — motor de reglas SpEL legacy

> **Purpose**: backend iFlow basado en reglas SpEL, restaurado desde el proyecto
> original. Es una implementación **independiente** y *deprecated*, no un shim
> ni un puente de compatibilidad.

---

## 1. Responsibilities

- Cargar `config/iflow-firewall/rules.yml` (`RuleConfigLoader`,
  `FirewallConfig`) con validación de esquema.
- Evaluar reglas SpEL (`ExpressionEvaluator`, `RuleExpressionEvaluator`) y
  producir un `RuleGraph` navegable.
- Enrutar mensajes con `DefaultRouter` aplicando la política por receptor/emisor.
- Validar el grafo del editor (`RuleGraph`, `Rule`, `TransformOp`) sin ejecutarlo.

## 2. Non-responsibilities

- No es el motor por defecto ni la única superficie de políticas: el hub
  `iflow` selecciona el backend activo (ADR-032).
- No sustituye a `iflow-vm` ni a `iflow-wasm`; los tres pueden estar instalados.
- No ejecuta el grafo de nodos del editor: el motor evalúa reglas SpEL.
- No contiene lógica de plataforma ni de entrega.

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Router`, `Message`, `PolicyOutcome` |
| `spring-expression` | Compile | Evaluación SpEL |
| `jackson-databind` / `jackson-annotations` | Compile | Grafo de reglas |
| `adventure-text-minimessage` / `-serializer-plain` | Compile | Render de plantillas |
| `snakeyaml` | Compile | Lectura de `rules.yml` |
| `textformatter`, `kernel` | Test only | Fixtures de integración |

## 4. Configuration

```text
<data>/config/iflow-firewall/rules.yml
```

La selección del motor vive en `<data>/config/iflow/config.yml`:

```yaml
engine: iflow-firewall
fallback-on-unavailable: true
config-version: "1.0"
```

El hub consulta la disponibilidad de cada backend y aplica la prioridad
`iflow-firewall` → `iflow-vm` → `iflow-wasm` cuando el configurado no está
disponible. No hay orden implícito por registro.

## 5. Main Components

| Component | Role |
|-----------|------|
| `IflowFirewallModule` / `IflowFirewallActivator` | Registro SPI y publicación de `IflowBackend` |
| `RuleConfigLoader` / `FirewallConfig` | Lectura y validación de `rules.yml` |
| `RuleGraph` / `Rule` | Modelo del grafo y de una regla |
| `ExpressionEvaluator` | Evaluación SpEL con superficie controlada |
| `DefaultRouter` | Router del backend |

## 6. Verification

```bash
./gradlew :src:iflow-firewall:test --no-daemon
```
