/* The engine arguments the watch build uses, defined once so the host smoke
 * test cannot drift from what actually ships.
 *
 *   -iwad <path>  the IWAD; Doom keys the game off this file's *name*
 *   -nosound      no sound module is compiled in, and this ROM has no usable
 *                 AudioTrack path anyway
 *   -mb 16        zone size; the engine's own default is 6 MiB
 */
#ifndef DOOM_ARGS_H
#define DOOM_ARGS_H

/* argv slots needed, NULL terminator included. */
#define DOOM_ARGV_MAX 7

/** Fills `argv` (DOOM_ARGV_MAX slots) and returns the argc to pass on. */
static int doom_watch_args(char *wad, char **argv) {
    argv[0] = "doom";
    argv[1] = "-iwad";
    argv[2] = wad;
    argv[3] = "-nosound";
    argv[4] = "-mb";
    argv[5] = "16";
    argv[6] = NULL;
    return 6;
}

#endif /* DOOM_ARGS_H */
