package ru.moonlightproject.dev.selene.paper;

import com.google.gson.GsonBuilder;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.moonlightproject.dev.selene.core.ConfigFiles;
import ru.moonlightproject.dev.selene.core.LanguageCatalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Split files are authoritative. Migration finishes by replacing config.yml last. */
final class PaperConfiguration {
    private static final Map<String,Set<String>> ROOTS = Map.of(
        "config.yml",Set.of("role","stage","language","console"),
        "interface.yml",Set.of("captcha-view","captcha","dialogs","presentation","resource-pack-provider"),
        "protection.yml",Set.of("packet-events-mode","secure-input","timeout-seconds","effects","visibility"),
        "limbo.yml",Set.of("limbo"));

    static YamlConfiguration load(Path directory, Function<String,InputStream> resources) throws Exception {
        Files.createDirectories(directory);
        Path main=directory.resolve("config.yml");
        YamlConfiguration old=Files.exists(main)?read(main):new YamlConfiguration();
        boolean migrating=old.getKeys(false).stream().anyMatch(key -> !ROOTS.get("config.yml").contains(key));
        if(migrating) {
            PaperConfigValidator.validate(legacyWithDefaults(old,resources).getValues(true));
            Path backup=directory.resolve("config.yml.legacy.bak");
            if(!Files.exists(backup))Files.copy(main,backup);
        }
        Files.createDirectories(directory.resolve("languages"));
        for(String locale:List.of("ru","en")) {
            Path target=directory.resolve("languages/"+locale+".json");
            if(!Files.exists(target)) {
                String json=resource(resources,"languages/"+locale+".json");
                if(migrating && locale.equals("ru")) {
                    Map<String,String> translated=new GsonBuilder().create().fromJson(json,new com.google.gson.reflect.TypeToken<Map<String,String>>(){}.getType());
                    for(String key:old.getKeys(true)) {
                        Object value=old.get(key);
                        if(translated.containsKey(key) && value instanceof String text)translated.put(key,text);
                        if(value instanceof List<?> lines)for(int i=0;i<lines.size();i++) {
                            if(translated.containsKey(key+"."+i) && lines.get(i) instanceof String text)translated.put(key+"."+i,text);
                        }
                    }
                    json=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(translated)+"\n";
                }
                ConfigFiles.writeAtomic(target,json);
            }
        }
        YamlConfiguration merged=new YamlConfiguration();
        // Main config last: a stopped migration can safely resume without losing old values.
        for(String file:List.of("interface.yml","protection.yml","limbo.yml","config.yml")) {
            YamlConfiguration defaults=new YamlConfiguration();defaults.loadFromString(resource(resources,"defaults/"+file));
            Path path=directory.resolve(file);
            boolean create=!Files.exists(path)||(file.equals("config.yml")&&migrating);
            if(create) {
                if(migrating)for(String key:defaults.getKeys(true)) {
                    if(!defaults.isConfigurationSection(key)&&old.contains(key))defaults.set(key,old.get(key));
                }
                ConfigFiles.writeAtomic(path,defaults.saveToString());
            }
            YamlConfiguration configured=read(path);
            for(String key:configured.getKeys(false))if(!ROOTS.get(file).contains(key))
                throw new IllegalArgumentException("Setting '"+key+"' does not belong in "+file);
            for(String key:defaults.getKeys(true))if(!defaults.isConfigurationSection(key))merged.set(key,defaults.get(key));
            for(String key:configured.getKeys(true))if(!configured.isConfigurationSection(key))merged.set(key,configured.get(key));
        }
        return merged;
    }
    private static YamlConfiguration legacyWithDefaults(YamlConfiguration old,Function<String,InputStream> resources)throws Exception {
        YamlConfiguration all=new YamlConfiguration();all.loadFromString(resource(resources,"config.yml"));
        for(String key:old.getKeys(true))if(!old.isConfigurationSection(key))all.set(key,old.get(key));
        return all;
    }
    private static YamlConfiguration read(Path path)throws Exception {
        YamlConfiguration yaml=new YamlConfiguration();yaml.load(path.toFile());return yaml;
    }
    private static String resource(Function<String,InputStream> resources,String name)throws IOException {
        try(InputStream stream=resources.apply(name)) {
            if(stream==null)throw new IOException("Missing bundled configuration: "+name);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
