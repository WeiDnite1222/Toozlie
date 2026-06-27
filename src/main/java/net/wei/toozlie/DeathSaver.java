package net.wei.toozlie;

public final class DeathSaver {
    public DeathStore store;
    private final Toozlie toozlie;
    public DeathPosCommand cmd;

    public DeathSaver(Toozlie plugin) {
        this.toozlie = plugin;
    }

    public void startSaver() {
        // Start store
        this.store = new DeathStore(toozlie);

        // Commands
        cmd = new DeathPosCommand(this, store);

        toozlie.getServer().getPluginManager().registerEvents(store, toozlie);
    }

    public void stopSaver() {
        if (store != null) store.flush();
    }

    public Toozlie getToozlie() {
        return toozlie;
    }
}
