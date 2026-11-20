# Limitaciones conocidas

- **Redes distintas:** funcionan a través del relé de Cloudflare (`relay/`), que hay que publicar una
  vez en tu cuenta. Las dos tienen que tener Internet. La vinculación (QR o código) se hace siempre en
  la misma red. Plan gratis: 100.000 solicitudes por día; si alguna vez se pasara, el relé deja de
  andar hasta el día siguiente (no cobra). Por el relé las órdenes tardan un poco más (≈ 0,1–0,3 s).
- **Programas como administrador:** Windows no deja que Lucy cierre o traiga al frente ventanas de
  programas abiertos como administrador.
- **Brillo:** sólo en notebooks (los monitores externos casi nunca lo permiten desde Windows).
- **Música con búsqueda:** con YouTube Music instalada desde Chrome/Edge, Lucy busca en su ventana
  (la abierta, o la abre) y toca el primer resultado con la accesibilidad de Windows; si YouTube
  cambia su página puede quedarse en los resultados sin reproducir. Para escribir en la ventana,
  Windows tiene que dejar traerla al frente. Con Spotify abre la búsqueda (Spotify no deja reproducir
  un resultado sin tocarlo). Sin app de música instalada, abre YouTube en el navegador.
- **Sin Internet en el teléfono:** el reconocimiento pasa a Vosk (entiende peor frases largas) y las
  preguntas generales no se pueden contestar con IA.
- **Pendrive en otra PC:** la clave de la PC está cifrada con el usuario de Windows; en otra
  computadora se crea una identidad nueva y hay que volver a vincular (es a propósito).
- **Probado en la nube:** Windows Server en la nube y un emulador Android. La red real (tu Wi-Fi, tu
  teléfono y tu PC) se prueba al usarlo.
