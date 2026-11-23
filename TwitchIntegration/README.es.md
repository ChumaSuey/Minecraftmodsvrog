# TwitchIntegration — credenciales y claves de API

> **Resumen:** hoy el mod solo **lee** chat de forma anónima, así que no hace falta
> ninguna clave. Cuando se agregue el envío de mensajes o EventSub, las
> credenciales las administra **la responsable del proyecto**, nunca el
> repositorio, el jar ni el chat.

## Quién las maneja

- La responsable de la cuenta de Twitch crea la aplicación en
  <https://dev.twitch.tv/console/apps>, guarda el **Client ID** y es la única que
  genera, rota o revoca tokens.
- El resto del equipo **no** recibe tokens. Si alguien necesita probar el envío,
  se habilita su usuario o se usa el bridge de solo lectura.
- Ninguna credencial se comparte por chat, issues, PRs, capturas ni commits.

## Reglas de oro

1. **Nunca** subir credenciales al repositorio: ni `.env`, ni
   `config/twitchintegration.json`, ni `credentials.json`, ni capturas de pantalla
   con el token visible.
2. **Nunca** empaquetarlas en el jar del mod. El jar se publica y cualquiera puede
   abrirlo.
3. **Nunca** imprimirlas en logs. El bridge no debe loggear tokens ni respuestas
   completas de OAuth.
4. Usar **app pública + Device Code Flow**: no hay `client secret` en una
   aplicación de escritorio. El token se obtiene desde el navegador y el refresh
   token queda solo en la máquina local.
5. Pedir **solo los scopes necesarios**.

## Dónde van (cuando exista autenticación)

| Qué | Dónde | Va al repo |
| --- | --- | --- |
| Client ID | `config/twitchintegration/credentials.json` o `TWITCH_CLIENT_ID` | no |
| Access / refresh token | `config/twitchintegration/credentials.json` o `TWITCH_OAUTH_TOKEN` | no |
| Configuración no secreta | `config/twitchintegration.json` | no (está ignorado) |
| `.env` para desarrollo del bridge | `TwitchIntegration/bridge/.env` (solo local) | no |

`config/` y `*.env` ya están en `.gitignore`. Si un archivo secreto necesita
permisos, en Linux/macOS usar `chmod 600`.

## Scopes previstos

- `chat:read` — leer chat (ya cubierto de forma anónima).
- `chat:edit` — enviar mensajes.
- `channel:read:redemptions`, `channel:read:subscriptions`,
  `moderator:read:followers`, `bits:read` — EventSub futuro (puntos de canal,
  suscripciones, seguidores, bits).

No pedir scopes "por si acaso": cada uno amplía lo que un token robado puede
hacer.

## Checklist antes de cada commit

- [ ] `git status` no muestra `config/`, `.env` ni `credentials.json`.
- [ ] No hay tokens en el diff (buscar `oauth:`, `Bearer`, `refresh_token`).
- [ ] No se agregaron capturas ni logs con credenciales.
- [ ] `.env` y `credentials.json` siguen ignorados.
- [ ] El jar no contiene archivos de configuración personal.

## Si se filtra una clave

1. Revocar el token en <https://dev.twitch.tv/console/apps>.
2. Regenerar las credenciales y actualizar solo la máquina de la responsable.
3. Avisar al equipo y rotar cualquier token derivado.
4. Si llegó a commitearse, revisar el historial (`git log -S "oauth:"`) y limpiar
   el commit, no solo borrar el archivo.

## Estado actual del proyecto

- **Solo lectura anónima:** no hay clave, ni `.env`, ni nada que proteger todavía.
- El endpoint `TwitchIntegrationApi.sendChat(...)` ya está cableado a `cmd.say`,
  pero el bridge actual responde `unsupported`. Cuando se implemente el envío,
  esta es la única pieza que necesitará credenciales.
