package com.flwolfy.ults.display;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every stock on its way out of the storage at this moment, one stream to a player.
 *
 * <p>A player has one stream at a time: starting another ends the one they had, so a second click never
 * races the first over the same stock. The streams are ticked by the storage itself, which is what makes
 * a take-everything a slow pour rather than one enormous operation.
 */
public final class UltsTakeAllStreams {

  private final List<UltsTakeAllStream> streams = new ArrayList<>();

  /**
   * Starts a stream, ending whatever the same player had running.
   *
   * @param stream the stream to run from the next tick on
   */
  void start(UltsTakeAllStream stream) {
    for (UltsTakeAllStream running : List.copyOf(streams)) {
      if (running.playerId().equals(stream.playerId())) {
        running.supersede();
        streams.remove(running);
      }
    }
    streams.add(stream);
  }

  /** One tick of every stream; the ones that have stopped take themselves out. */
  public void tick() {
    streams.removeIf(stream -> !stream.tick());
  }
}
