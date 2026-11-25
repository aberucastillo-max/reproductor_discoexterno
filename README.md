# Explorador USB NTFS 2.0: reproductor privado para tu disco (Android, sin root)

App para ver y reproducir el contenido de un disco externo **NTFS** (por ejemplo un WD Elements formateado
en Windows) en tablets Android que no lo reconocen, como la Galaxy Tab S6 Lite. La interfaz es oscura, con
menú lateral, mosaicos con portada y barra de reproducción.

## Qué hace

- **Carpetas, Vídeos, Fotos, Música**: todo el contenido del disco. Los vídeos y la música se agrupan
  por carpeta y las fotos por mes. Se puede ver en mosaicos o en lista.
- **Reproductor de vídeo propio** (motor libVLC con FFmpeg). Reproduce AVI, MP4, MPG/MPEG-1/2, MKV, MOV,
  WMV, FLV, VOB, TS, 3GP, WebM, DivX/Xvid, H.264/H.265, AC3, DTS, etc. Incluye avance de ±10 s
  (también con doble toque), selección de pista de audio y de subtítulos internos, ajuste de imagen y velocidad.
- **Visor de fotos**: deslizar para pasar de una a otra, pellizcar o doble toque para ampliar. Al acercarse
  carga la zona visible a resolución completa, para ver la foto con todo detalle. También tiene presentación automática.
- **Música** con barra de "reproduciendo" que sigue sonando mientras navegas, y modo aleatorio.
- **Buscar** por nombre (sin distinguir mayúsculas ni acentos), carpeta, tipo, extensión, tamaño y fecha de
  modificación. Los resultados se pueden ordenar y agrupar por carpeta, extensión, tipo, mes, año o tamaño.
  La búsqueda usa un índice que se crea en segundos leyendo la MFT del disco de forma secuencial.
- **Artistas y grupos** (actores, cantantes, personas, series…):
  - mantén pulsado un archivo, marca varios y pulsa «Añadir a grupo»;
  - **Sugerir grupos** propone grupos analizando los nombres: «Artista - Título», «feat.», carpetas con
    nombre propio y nombres repetidos como «Brad Pitt»;
  - cada grupo tiene **portada**: automática o una imagen del disco que tú elijas;
  - reproducir todo o en aleatorio, y filtrar por Vídeos, Fotos o Música.
  - Los grupos son **virtuales**: no mueven ni cambian nada en el disco. Siguen funcionando aunque luego
    renombres o muevas los archivos en Windows.

## Integridad del disco

- **Solo lectura**: la app nunca envía comandos de escritura al disco.
- **Expulsar disco** sigue este orden:
  1. espera a que termine cualquier lectura en curso y detiene la reproducción;
  2. `SYNCHRONIZE CACHE` vacía la caché del disco, como pide la norma antes de parar el motor;
  3. ATA `STANDBY IMMEDIATE` (por ATA PASS-THROUGH) para el motor y aparca los cabezales.
     Es el método que usa Linux, porque `START STOP UNIT` no siempre detiene un disco
     conectado por un adaptador USB-SATA;
  4. `START STOP UNIT` sin IMMED (espera a la parada) y después con LoEj (medio expulsado).
- **La app sujeta el disco en reposo hasta que desconectas el cable.** Si soltara la conexión,
  Android volvería a conectar su propio driver, arrancaría el disco otra vez y, al desenchufarlo,
  diría que no se retiró con seguridad. La pantalla muestra «Ya puedes desconectar el disco» y,
  al quitar el cable, «Disco retirado correctamente».
- **Comprobación antes de abrir el disco**: si Android ya lo montó por su cuenta (algunos Samsung
  montan NTFS en solo lectura), la app pide desmontarlo primero en Ajustes → Almacenamiento.
- **Protección eléctrica**:
  - si el disco deja de responder durante más de 5 s, la app reinicia la conexión y avisa
    (es el síntoma de falta de energía) recomendando expulsarlo;
  - con batería baja avisa al 15 % y, si no está cargando, **expulsa sola al 5 %**, para que la
    tablet no se apague con el disco girando;
  - la pantalla no se apaga sola mientras el disco está montado.
- **No se cierra con el disco conectado**: Atrás pide expulsar primero y, con «Fijar app», no se
  puede salir. Si aun así se cierra desde "recientes", el servicio interno detiene el disco.

## Confidencialidad

| Medida | Efecto |
|---|---|
| **Sin permiso de Internet** (la app no declara ningún permiso) | Android impide a nivel de sistema que la app se conecte a la red. |
| `FLAG_SECURE` en todas las pantallas y diálogos | Bloquea capturas, grabación de pantalla, la miniatura en "recientes" y la proyección. |
| No usa "Compartir", "Abrir con" ni portapapeles, y no copia archivos | Ningún archivo ni nombre pasa a otras apps. |
| Sin miniaturas ni caché en disco | Las miniaturas y el índice viven solo en memoria y se borran al expulsar. |
| Grupos cifrados con AES-256-GCM, con la clave en el Android Keystore | Solo se pueden leer en esta tablet y con esta app. Excluidos de las copias de seguridad. |
| Teclado sin aprendizaje ni sugerencias, sin autocompletado | Lo que escribes al buscar no se guarda en el teclado. |
| Sin notificaciones ni controles en la pantalla de bloqueo | Ningún título aparece fuera de la app. |
| Sin registros (logs) de nombres de archivo | |
| libVLC sin scripts Lua, sin acceso de red a metadatos y sin buscar subtítulos externos | |

Límites honestos: ninguna app puede protegerse de alguien con acceso root a la tablet ni de una foto
hecha con otro móvil. Tampoco de un servicio de accesibilidad que tú mismo hayas autorizado
(Ajustes → Accesibilidad). Para extraer miniaturas de vídeo, Android usa su servicio multimedia
interno, que funciona dentro de la propia tablet.

## Instalar

1. Si tenías la versión 1.0, **desinstálala** primero (está firmada con otra clave).
2. Abre `ExploradorUSB-NTFS.apk` en la tablet y permite "instalar apps desconocidas".
3. Para que «Fijar app» funcione, activa en Ajustes → Seguridad → **Fijar ventanas** (o "Anclar aplicaciones").

## Compilar y probar

```bash
./build.sh            # genera el APK (descarga libVLC, la API de Android 13 y ASM de Maven Central)
./test/run_tests.sh   # discos NTFS de prueba con ntfs-3g: lectura, índice MFT, búsqueda y sugerencias
```

`tools/Desugar11.java` convierte las clases Java 11 de libVLC a Java 8 para que las acepte `dx`
(el compilador de Android disponible en Debian).
