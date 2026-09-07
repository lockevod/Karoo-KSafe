# KSafe — Configuración fácil (empieza aquí)

> **¿Nuevo en KSafe? Lee esto primero.** Es la versión en lenguaje claro: qué tocar, qué
> poner y qué ignorar. Sin fórmulas, sin jerga. Cuando quieras el detalle completo de algo,
> cada sección enlaza hacia la guía a fondo.
>
> 🇬🇧 Prefer English? → [easy-setup.md](easy-setup.md)

KSafe es una app de seguridad gratuita para tu Karoo. Hace dos cosas:

1. **Si algo sale mal** — una caída, una parada brusca, un check-in perdido o (con una banda
   de pulso) un evento médico — puede **enviar una alerta a una persona que tú elijas**.
2. **Antes de que salga mal** — puede **recordarte que comas y bebas** para que no te dé la
   pájara ni te deshidrates.

No necesitas entender cómo funciona nada de esto. Necesitas hacer tres cositas, abajo.

---

## ✅ La configuración mínima en 5 minutos

Haz solo esto y ya estás protegido. Todo lo demás es opcional.

### 1. Elige UNA forma de enviar alertas (el "Provider")

Abre la pestaña **Proveedor** (en inglés "Provider") y elige la que te encaje:

- **¿La quieres gratis y rápida?** → usa **ntfy** o **Telegram**.
- **¿Tu contacto vive en WhatsApp?** → usa **CallMeBot**.
- **¿Quieres la notificación más fiable y no te importa un pago único de ~5 $?** → **Pushover**.

Las dos más fáciles están explicadas paso a paso más abajo: [Senders sin complicaciones](#-senders-sin-complicaciones).

### 2. Pulsa **Probar envío** (en inglés, "Test Send")

Después de rellenar el provider, pulsa **Probar envío**. Tu contacto (o tu propio móvil) debería
recibir un mensaje en segundos. **Si no llega nada, no te lo saltes** → ve a
[¿No llegan las alertas?](#-no-llegan-las-alertas-las-3-causas-habituales). Una app de
seguridad sin probar no es una app de seguridad.

### 3. Escribe tu mensaje de emergencia

En la pestaña **Seguridad** (Safety), edita el **mensaje de emergencia** para que tu contacto sepa que
eres tú y qué hacer (p. ej. *"Alerta automática de la bici de Pete. Puede que me haya caído.
Mi ubicación: {location}"*). La etiqueta `{location}` se sustituye por un enlace de mapa en
vivo automáticamente.

**Eso es todo el mínimo.** La detección de caídas ya está activa con valores por defecto
sensatos. El resto de esta guía es "para mejorar", pestaña a pestaña.

> ⚠️ KSafe envía los mensajes a través de **la conexión a internet de tu móvil** (vía la app
> Hammerhead Companion). Sin cobertura en el móvil no hay alerta. Esto vale para cualquier
> provider.

---

## 📑 Pestaña a pestaña, en lenguaje claro

Tu Karoo muestra seis pestañas. Esto es para qué sirve cada una, qué poner y qué dejar
tranquilo.

### 🛡️ Seguridad (Safety) — el núcleo (configúralo)

Es el corazón de KSafe y funciona de fábrica. Lo que merece la pena tocar:

- **Sensibilidad de detección de caídas** — elige la que case con tu forma de rodar:
  - **Low (baja)** — ⛰ MTB / gravel / enduro. Solo un golpe muy fuerte la dispara. Úsala si
    los caminos bacheados te provocan falsas alarmas.
  - **Medium (media)** — 🚴 la recomendada por defecto. Buena para carretera y mixto.
  - **High (alta)** — 🏁 solo carretera lisa / pista. Detecta caídas más leves, pero el
    terreno irregular dará falsas alarmas: no la uses fuera de asfalto.
- **Mensaje de emergencia** — el texto que recibe tu contacto (ver paso 3 arriba).
- **Temporizador de check-in** *(opcional)* — KSafe te pide pulsar un botón cada cierto
  tiempo; si no lo haces (p. ej. estás herido y no puedes), da la alarma. Útil en salidas en
  solitario. Desactivado por defecto.

Puedes dejarlo todo por defecto salvo el mensaje y estar bien protegido.
*(Los botones de "Estoy bien" de un toque y los avisos de inicio/fin de ruta están en la
pestaña **Acciones** — ver abajo.)*

### ❤️ Salud (Health) — solo si ruedas con banda de pulso (opcional)

Sáltate toda esta pestaña si no usas sensor de pulso. Si lo usas:

- **Detección médica** — vigila si tu pulso se aplana o se desploma, y lo trata como una
  caída (avisa a tu contacto). Razonable dejarla activa si llevas banda.
- **Monitor de bienestar** — te avisa a *ti* (pitido + en pantalla, **no** se envía a
  contactos) si el pulso se mantiene peligrosamente alto o se desacopla — señal de
  sobreesfuerzo o calor. Desactivado por defecto; actívalo si lo quieres.

Detalle completo: [Referencia de Health y Fueling](health-fueling.md).

### 🍫 Nutrición (Fueling) — recordatorios de comer y beber (opcional, mira la versión simple abajo)

Es la pestaña que más cuesta entender, así que tiene su propia sección en lenguaje claro:
[Fueling sin complicaciones](#-fueling-sin-complicaciones). En corto: **no** escribes ningún
número de calorías ni carbohidratos — KSafe lo calcula. Tú solo le dices tu edad y sexo, y
cada cuánto quieres que te recuerde.

### 🔘 Acciones (Actions) — botones y avisos extra (opcional)

Aquí viven tres cosas, todas opcionales:

- **Botones de mensaje personalizado** — mensajes de un toque tipo *"Estoy bien"* / *"Voy de
  vuelta"* que disparas desde un campo de datos en tu pantalla de ruta. Útiles y fáciles.
- **Avisos de inicio / fin de ruta** — envían a tu contacto un *"ruta iniciada / terminada"*,
  con un enlace de seguimiento en vivo opcional.
- **Webhooks** *(avanzado)* — lanzar cualquier comando de internet desde un botón del Karoo
  (abrir el garaje, disparar Home Assistant, etc.). Sáltatelo salvo que lo quieras a propósito.
  Detalle: [Recetario de webhooks](webhooks-cookbook.md).

### 📨 Proveedor (Provider) — quién recibe tus alertas (configúralo — es el paso 1 de arriba)

Donde eliges y configuras cómo se envían las alertas. Cubierto en
[Senders sin complicaciones](#-senders-sin-complicaciones).

Algo que conviene saber: cada contacto tiene un ajuste de qué recibe —
**Todas** (por defecto), **SOS** o **Info**. La mayoría lo deja en **Todas**.

Si dejas esta pestaña vacía, KSafe te avisa al empezar cada ruta de que las alertas no
se enviarán. ¿No usas alertas a propósito? Activa **"No usaré alertas — no me avises al
empezar la ruta"** en ese mismo aviso y deja de salir. Todo lo demás — pitidos de caída,
pantalla de SOS, check-in, recordatorios de nutrición — sigue funcionando igual.

### ⚙️ Ajustes (Settings) — mantenimiento (déjalo casi todo como está)

- El **idioma** sigue al de tu Karoo automáticamente (inglés o español) — no hay selector.
- **Ayuda a mejorar la detección de caídas** — un interruptor opcional que envía datos
  anónimos de los sensores tras las rutas (sin GPS, sin mensajes, sin nombres). Activarlo
  ayuda de verdad a mejorar la detección. Tú decides.
- **Copia de seguridad / restaurar** tus ajustes, y **Escribir fueling al FIT** (desactivado
  por defecto) — ambos opcionales.

---

## 📨 Senders sin complicaciones

Solo necesitas **uno**. Aquí van los dos más fáciles, explicados enteros. (Para
CallMeBot/WhatsApp y Pushover, mira la [guía completa de providers](messaging-providers.md) —
misma idea, un par de pasos más.)

### El más fácil: ntfy (gratis, sin cuenta)

ntfy es una app de notificaciones gratuita. Eliges un "nombre de canal" secreto y KSafe grita
en él.

1. En el móvil que debe recibir las alertas, instala la app **ntfy** (Android o iPhone).
2. En ntfy, pulsa **+**, escribe un **topic** difícil de adivinar
   (p. ej. `ksafe-pete-7x4k9`) y pulsa **Subscribe**.
3. En el Karoo: pestaña **Proveedor** → elige **ntfy** → escribe el **mismo** topic → **Probar envío**.

Ya está. Para avisar a varias personas, que cada una se suscriba al mismo topic.

### También fácil: Telegram (gratis, ilimitado)

1. En Telegram, busca **@BotFather**, envía `/newbot` y sigue los pasos. Te da un **Bot Token**
   largo — cópialo.
2. Consigue el **Chat ID** de quien deba recibir las alertas: en **su** móvil, busca
   **@userinfobot** en Telegram y envía `/start`. Responde con un número — ese es el Chat ID.
3. **Lo que todo el mundo olvida:** esa persona tiene que abrir Telegram, encontrar **tu**
   bot nuevo y pulsar **Start** una vez. Hasta que lo haga, los mensajes fallan en silencio.
4. En el Karoo: pestaña **Proveedor** → **Telegram** → pega el **Bot Token** → pon el **Chat ID**
   en Recipient 1 → **Probar envío**.

> Puedes guardar la configuración de los cuatro providers a la vez — solo se usa el que
> seleccionas, y cambiar de uno a otro nunca borra los demás.

---

## 🍫 Fueling sin complicaciones

La guía técnica habla de estimadores de quema, niveles y techos de absorción intestinal.
Olvídate de todo eso. Esto es lo que de verdad haces.

### La idea clave (léela una vez)

KSafe no puede medir el azúcar en tu sangre — ningún sensor de bici puede. Así que en su
lugar **estima cuánto has quemado** a partir de tu pulso o tu potencia, **cuenta lo que has
comido** (cada vez que pulsas un botón de "registro") y **te avisa cuando te quedas atrás**.
Tú no pones ningún número objetivo de carbohidratos — KSafe calcula la quema por ti.

### Carbohidratos: 3 cosas que hacer

1. **Empareja un sensor.** Lo mejor es un **medidor de potencia**; lo siguiente, una **banda
   de pulso**. Sin ninguno, KSafe no puede estimar la quema (el campo muestra *"Pair HR/Pwr"*).
2. **Si solo tienes pulso:** en la pestaña **Nutrición** (Fueling), rellena tu **Edad** y **Sexo**. Es el
   único dato personal que necesita — hace la estimación bastante más precisa.
3. **Elige cada cuánto recibir el recordatorio.** Deja los valores por defecto (avísame
   cuando vaya ~25 g por detrás, y como mucho cada 10 min) salvo que te resulten muy pesados
   o muy escasos.

### Hidratación: 1 cosa que hacer

Pon un **objetivo de bebida por hora**. Usa esta guía orientativa según el tiempo:

| Tiempo | Bebida por hora |
|---|---|
| Fresco (menos de 15 °C) | 400–600 ml |
| Suave (15–22 °C) | 600–800 ml *(por defecto 750)* |
| Templado (22–28 °C) | 800–1100 ml |
| Caluroso (28–32 °C) | 1100–1400 ml |
| Muy caluroso (más de 32 °C) | 1400–1800 ml |

El valor por defecto (750 ml/h) va bien para un día suave — súbelo en verano. Si prefieres no
pensar en ello, activa **Dynamic estimate** y KSafe ajusta el objetivo según tu esfuerzo y la
temperatura.

### "¿Qué toco?"

KSafe solo sabe que has comido o bebido **cuando tocas un botón de registro**. Añade los
campos de registro que quieras a tu pantalla de ruta (en el editor de perfiles del Karoo):

- **Slots de registro de carbos** (p. ej. *"Gel"*, *"Barrita"*) — un toque = un ítem registrado.
- **Slots de registro de bebida** (p. ej. *"Bidón"*) — un toque = una bebida registrada.
- **Botón combinado** (en el editor de perfiles se llama *Fuel Combo*) — registra una bebida
  *y* sus carbos en un solo toque.

¿Lo tocaste sin querer? Vuelve a tocar el mismo slot en ~5 segundos para deshacerlo.

Eso es todo: empareja un sensor, rellena edad/sexo, pon un objetivo de bebida y toca cuando
comas o bebas. Ciencia completa y consejos de calibración: [Referencia de Health y Fueling](health-fueling.md).

---

## 🔧 ¿No llegan las alertas? Las 3 causas habituales

Si el **Probar envío** no llegó, casi siempre es una de estas:

1. **Sin conexión en el móvil.** KSafe envía a través de internet del móvil vía la app
   Hammerhead Companion. Asegúrate de que el móvil tiene conexión y Companion está en marcha.
2. **Telegram: el contacto nunca pulsó Start.** Un bot de Telegram solo puede escribir a
   alguien que lo haya abierto y pulsado **Start** al menos una vez. Es la causa nº 1. (Mira
   los pasos de Telegram arriba.)
3. **CallMeBot: configurado en el móvil equivocado.** CallMeBot hay que activarlo **desde el
   WhatsApp del contacto**, no el tuyo, y cada contacto tiene su **propia** clave. Mira la
   [guía de providers](messaging-providers.md).

¿Sigues atascado? La guía completa de providers tiene una nota de solución de problemas para
cada opción: [messaging-providers.md](messaging-providers.md).

---

> ¿Quieres el detalle a fondo de algún tema? Empieza por el [README](../README.md), que enlaza
> todas las guías detalladas: detección de caídas, médico/bienestar, ciencia del fueling,
> webhooks y más.
