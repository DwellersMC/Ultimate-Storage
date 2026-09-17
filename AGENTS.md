# Project conventions

- Target Minecraft 26.2 with Fabric Loader 0.19.3 and Java 25.
- Run `./gradlew test` for tests and `./gradlew build` for the complete verification build.
- Server-visible text is rendered through `UltsLangManager`; keep `en_us.json`, `zh_cn.json`, and `zh_tw.json` keys synchronized.
- Persistent storage belongs in overworld `SavedData`; never store aggregate contents in barrel or player NBT.
