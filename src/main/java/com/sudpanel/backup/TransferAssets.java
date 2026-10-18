package com.sudpanel.backup;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** 完整迁移的外部资产。只处理配置、截图与历史归档，不触碰数据库锁或恢复目录。 */
final class TransferAssets {
    static final long LIMIT = 256L * 1024 * 1024;
    static final int FILE_LIMIT = 25000;
    static final List<String> ROOTS = List.of("config", "screenshots", "archives");
    record Item(String path, long bytes, String sha256) {}
    record Inventory(Map<String, Boolean> roots, List<Item> files) {}
    private TransferAssets() {}

    static Path target(Path db, String name) {
        return switch (name) {
            case "config" -> db.getParent().getParent().resolve("config");
            case "screenshots" -> db.getParent().resolve("screenshots");
            case "archives" -> BackupFiles.root(db).resolve("archives");
            default -> throw new IllegalArgumentException("迁移资产目录无效");
        };
    }
    static void noLinks(Path path) {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("迁移路径包含符号链接");
    }
    static String safeName(String text) {
        if (text == null || text.contains("\\") || text.contains("\0")) throw new IllegalArgumentException("迁移文件路径无效");
        Path p = Path.of(text);
        if (p.isAbsolute() || !p.normalize().toString().equals(text) || p.getNameCount() < 2
                || !ROOTS.contains(p.getName(0).toString()) || text.contains("//") || text.endsWith("/"))
            throw new IllegalArgumentException("迁移文件路径越界");
        return text;
    }
    private static Item item(Path root, Path file) throws Exception {
        noLinks(file);
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > LIMIT) throw new IllegalArgumentException("迁移资产不是普通文件或超过容量");
        String sha = BackupFiles.sha(file);
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) throw new IllegalArgumentException("资产正在变化，请稍后重新打包");
        return new Item(root.relativize(file).toString().replace(File.separatorChar, '/'), before.size(), sha);
    }
    private static List<Path> regularFiles(Path root) throws Exception {
        noLinks(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("迁移资产根不是目录");
        var files = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                noLinks(dir); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IllegalArgumentException("迁移资产包含特殊文件");
                files.add(file);
                if (files.size() > FILE_LIMIT) throw new IllegalArgumentException("迁移文件数量超过限制");
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(Path::toString)); return files;
    }
    static Inventory inventory(Path db) throws Exception {
        var roots = new LinkedHashMap<String, Boolean>(); var items = new ArrayList<Item>(); long bytes = 0;
        for (String name : ROOTS) {
            Path root = target(db, name); noLinks(root); boolean exists = Files.exists(root, LinkOption.NOFOLLOW_LINKS); roots.put(name, exists);
            if (exists) for (Path file : regularFiles(root)) {
                Item local = item(root, file); bytes += local.bytes();
                if (bytes > LIMIT || items.size() >= FILE_LIMIT) throw new IllegalArgumentException("外部资产超过256MB或25000文件");
                items.add(new Item(name + "/" + local.path(), local.bytes(), local.sha256()));
            }
        }
        return new Inventory(roots, List.copyOf(items));
    }
    static String fingerprint(Path db) throws Exception { return hash(BackupFiles.JSON.writeValueAsBytes(inventory(db))); }
    static String hash(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }

    static Inventory capture(Path db, Path destination) throws Exception {
        if (Files.exists(destination)) throw new IllegalArgumentException("迁移暂存目录已存在");
        BackupFiles.privateDir(destination); Inventory before = inventory(db);
        for (String name : ROOTS) if (before.roots().get(name)) BackupFiles.privateDir(destination.resolve(name));
        for (Item entry : before.files()) {
            Path relative = Path.of(entry.path()); Path original = target(db, relative.getName(0).toString()).resolve(relative.subpath(1, relative.getNameCount()));
            Path output = destination.resolve(entry.path()); BackupFiles.privateDir(output.getParent());
            Files.copy(original, output); BackupFiles.privateFile(output);
        }
        validate(destination, before);
        if (!before.equals(inventory(db))) throw new IllegalArgumentException("资产在打包期间变化，请重试");
        BackupFiles.json(destination.resolve("inventory.json"), before); return before;
    }
    static Inventory read(Path directory) throws Exception {
        noLinks(directory.resolve("inventory.json"));
        if (Files.size(directory.resolve("inventory.json")) > 8L * 1024 * 1024) throw new IllegalArgumentException("资产清单超限");
        Inventory value = BackupFiles.JSON.readValue(directory.resolve("inventory.json").toFile(), Inventory.class);
        validate(directory, value); return value;
    }
    static void validate(Path directory, Inventory value) throws Exception {
        noLinks(directory);
        if (value == null || value.roots() == null || !value.roots().keySet().equals(new HashSet<>(ROOTS))
                || value.roots().values().stream().anyMatch(Objects::isNull) || value.files() == null || value.files().size() > FILE_LIMIT)
            throw new IllegalArgumentException("迁移资产清单无效");
        var expected = new HashSet<String>(); long bytes = 0;
        for (Item entry : value.files()) {
            String name = safeName(entry.path());
            if (!expected.add(name) || !Boolean.TRUE.equals(value.roots().get(name.substring(0, name.indexOf('/'))))
                    || entry.bytes() < 0 || entry.bytes() > LIMIT || entry.sha256() == null || !entry.sha256().matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("资产清单存在重复或无效记录");
            Path file = directory.resolve(name); Item actual = item(directory, file);
            if (!entry.equals(actual)) throw new IllegalArgumentException("迁移资产长度或SHA256不匹配");
            bytes += entry.bytes(); if (bytes > LIMIT) throw new IllegalArgumentException("资产总量超过256MB");
        }
        var actual = new HashSet<String>();
        for (Path file : regularFiles(directory)) {
            String name = directory.relativize(file).toString().replace(File.separatorChar, '/');
            if (!name.equals("inventory.json")) actual.add(name);
        }
        if (!actual.equals(expected)) throw new IllegalArgumentException("迁移资产有未登记或缺失文件");
    }
    static void deleteTree(Path path) throws Exception {
        noLinks(path); if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) throw new IOException("迁移目录包含符号链接"); Files.delete(p); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path p, IOException failure) throws IOException {
                if (failure != null) throw failure; Files.delete(p); return FileVisitResult.CONTINUE;
            }
        });
    }
    static void replace(Path db, Path assets) throws Exception {
        Inventory value = read(assets);
        for (String name : ROOTS) {
            Path target = target(db, name); noLinks(target); Path temporary = null;
            try {
                if (value.roots().get(name)) {
                    BackupFiles.privateDir(target.getParent());
                    temporary = Files.createTempDirectory(target.getParent(), ".sudpanel-transfer-" + name + "-");
                    BackupFiles.privateDir(temporary);
                    Path source = assets.resolve(name);
                    if (Files.exists(source)) for (Path file : regularFiles(source)) {
                        Path output = temporary.resolve(source.relativize(file)); BackupFiles.privateDir(output.getParent());
                        Files.copy(file, output); BackupFiles.privateFile(output);
                    }
                }
                deleteTree(target);
                if (temporary != null) Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                if (temporary != null) deleteTree(temporary);
            }
        }
    }
}
