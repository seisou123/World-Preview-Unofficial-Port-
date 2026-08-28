# CHANGELOG — World Preview Fork

## 1.4.2

### Fixes

- Fixed preview map not loading when dragged to certain positions — queue handshake is now locked, and the viewport force-requeues unsampled areas when sampling is idle
- Fixed initialization failure from cross-loader configs — dimension identifiers with null namespace/path are now treated as unset and rewritten as `"namespace:path"` strings

### UI

- Preview page: unified Biomes / Structures / Seeds rail buttons (gray-to-black translucent theme); selected tab no longer darkens its background — marked only by outline and full-white text, all three identical at rest