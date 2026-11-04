package uz.sentinel.guard.paper.util;

import org.bukkit.entity.Player;
import uz.sentinel.guard.network.IpAddresses;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.InetSocketAddress;

public final class DataFiles {
    private DataFiles() {}

    /** Writes via a temporary file so a crash mid-save never leaves a truncated data file. */
    public static void writeAtomically(File file, String content) throws IOException {
        Path target = file.toPath();
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(file.getName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Normalized IPv4/IPv6 address of an online player, or null when unknown. */
    public static String ip(Player player) {
        if (player == null) return null;
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) return null;
        return IpAddresses.normalize(address.getAddress().getHostAddress());
    }
}
