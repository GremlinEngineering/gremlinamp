package ru.oopssre.gremlinamp;

enum TransportState {
    IDLE, LOADING, LOADING_PAUSED, PLAYING, PAUSED, CONTINUING, ENDED, ERROR;

    enum Event { LOAD, BUFFER, READY, PAUSE, RESUME, CONTINUE, FINISH, FAIL }

    TransportState on(Event event) {
        return switch (event) {
            case LOAD -> LOADING;
            case BUFFER -> this == PLAYING ? LOADING : this;
            case READY -> switch (this) {
                case LOADING -> PLAYING;
                case LOADING_PAUSED -> PAUSED;
                default -> this;
            };
            case PAUSE -> switch (this) {
                case LOADING -> LOADING_PAUSED;
                case PLAYING -> PAUSED;
                case CONTINUING -> ENDED;
                default -> this;
            };
            case RESUME -> switch (this) {
                case LOADING_PAUSED -> LOADING;
                case PAUSED -> PLAYING;
                default -> this;
            };
            case CONTINUE -> CONTINUING;
            case FINISH -> ENDED;
            case FAIL -> ERROR;
        };
    }

    boolean wantsPlayback() {
        return this == LOADING || this == PLAYING || this == CONTINUING;
    }

    boolean canSeek() {
        return this == PLAYING || this == PAUSED;
    }

    boolean canAdvance(boolean appended) {
        return switch (this) {
            case IDLE, CONTINUING -> false;
            case ENDED, ERROR -> appended;
            default -> true;
        };
    }
}
