Rattlesnake
===========

The mod adds rattlesnakes to the Las Venturas desert. They blend into the environment, react to nearby entities, and can bite CJ at close range.

An optional cobra can be enabled in Los Santos through cleo\Snake.ini. It is disabled by default because cobras are not native to North America. Fully close and reopen the game after changing Cobra.

Requirements
------------
- Classic GTA San Andreas for Windows (not mobile or Definitive Edition)
- ASI Loader and Mod Loader
- CLEO 4
- CLEO+ 1.2.0 or newer
- NewOpcodes

Installation
------------
Move the complete "Rattlesnake" folder into the game's "modloader" folder. Keep the cleo, ModelsQa, and SoundsQa directories together and do not rename their files. Then fully restart the game.

Configuration
-------------
cleo\Snake.ini:
- Volume = 0.0 to 1.0 (default 0.6)
- Cobra = 0 or 1 (default 0)

cleo\SnakeModels.ini is a runtime cache. Do not edit it or copy populated values from another game session or installation.

Technical note
--------------
Each snake intentionally uses ten separate static DFF files as animation frames. This CLEO+ render-object design avoids reserving or replacing a dedicated GTA model ID. These files are not accidental duplicates and must not be merged or removed.

Credits
-------
Script: ArtemQa146
Models, animation frames, sounds: Tarzan_3

by ArtemQa146

https://libertycity.net/user/Artem.1.9.9.6/
https://libertycity.ru/user/Artem.1.9.9.6/
https://www.gtainside.com/user/ArtemQa146
https://youtube.com/@ArtemQa146
