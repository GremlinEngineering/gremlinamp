#include <atari_bridge.c>

void gremlin_atari_close(void) {
    if (loaded) retro_unload_game();
    if (initialized) retro_deinit();
    loaded = initialized = 0;
}
