# Privacy

Lumen Sync has no accounts, advertisements, analytics, telemetry, or Lumen-operated server. Folder contents are transferred by the bundled Syncthing core directly between approved devices over encrypted connections.

Syncthing may use its public discovery and relay infrastructure to locate and connect devices when a direct connection is unavailable. Those services can observe network metadata such as IP addresses and device discovery identifiers, but relays do not receive plaintext file contents. Operators who do not want those services can change the generated Syncthing configuration, although that is not exposed in the simple UI yet.

The app stores its UI settings and Syncthing configuration locally in app-private storage. On Android, broad file access is requested solely so the foreground service and native core can read and write the one folder the user selects. Camera access is used only by the QR scanner. Notification access is used for the visible sync-session notification.

Uninstalling Lumen Sync removes app-private settings, but Android and desktop operating systems do not remove the synchronized folder itself.
