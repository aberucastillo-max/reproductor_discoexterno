# Explorador USB NTFS (Android, sin root)

App para leer discos externos **NTFS** (como un WD Elements formateado en Windows) en tablets
Android que no los reconocen, por ejemplo la Galaxy Tab S6 Lite.

- **Solo lectura**: la app nunca escribe en el disco, así que no hay riesgo de dañar tus datos.
- **Sin root**: habla con el disco directamente por USB con la API oficial de Android.
- Navega por carpetas y abre fotos, vídeos (puedes avanzar y retroceder), música y PDF con las apps de la tablet.
- Copia archivos o carpetas enteras a la tablet (mantén pulsado un elemento).

## Instalar en la tablet

1. Descarga `ExploradorUSB-NTFS.apk` en la tablet.
2. Ábrelo. Android pedirá permiso para "instalar apps desconocidas" desde el navegador o el gestor de archivos: actívalo.
3. Pulsa **Instalar**. Si Play Protect avisa de que la app es desconocida, pulsa **Más detalles → Instalar de todos modos**.

## Usar

1. Conecta el disco con el adaptador OTG. Si sale "¿Abrir Explorador USB NTFS?", marca *Usar siempre* y acepta.
2. Si abres la app a mano, acepta el permiso de USB que aparece.
3. Espera unos segundos mientras arranca el disco. Después verás tus carpetas.
4. **Tocar** abre una carpeta o un archivo. **Mantener pulsado** sirve para copiar a la tablet.
   La primera vez te pedirá elegir la carpeta de destino, por ejemplo *Download*.
5. Antes de desconectar el disco, cierra la app.

### Si no aparece el disco

La app muestra qué dispositivos USB detecta Android:

- **"No se detecta ningún dispositivo USB"**: es el adaptador OTG o falta de energía. Un WD Elements
  Portable sin cargador propio suele necesitar un **hub USB-C con carga (PD)**.
- **"El disco no termina de arrancar"** o errores de lectura: casi seguro, falta de energía.

## Compilar

```bash
./build.sh            # genera ExploradorUSB-NTFS.apk (sin Android Studio)
./test/run_tests.sh   # pruebas del lector NTFS con discos de prueba creados con ntfs-3g
```

Las pruebas comparan, archivo por archivo (SHA-256), lo que lee la app con lo que ve `ntfs-3g`. Cubren:
NTFS sin tabla de particiones, MBR, GPT con sectores lógicos de 4096 bytes, clústeres de 512 B a 64 KB,
carpetas de miles de archivos, nombres Unicode/emoji, archivos dispersos, comprimidos (LZNT1),
archivos partidos en más de 1.700 fragmentos ($ATTRIBUTE_LIST) y enlaces duros.

No soporta archivos cifrados con EFS.

## Estructura

| Archivo | Qué hace |
|---|---|
| `core/Ntfs.java` | Lector NTFS (MFT, índices, tramos, compresión) |
| `core/Partitions.java` | Tablas de particiones MBR / lógicas / GPT |
| `core/Disk.java` | Lectura por bytes con caché |
| `UsbMassStorage.java` | USB Bulk-Only + comandos SCSI (solo lectura) |
| `FilesProvider.java` | Permite a otras apps abrir archivos del disco directamente |
| `MainActivity.java` | Interfaz |
