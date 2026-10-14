package ru.moonlightproject.dev.selene.velocity;

import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import ru.moonlightproject.dev.selene.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

record VelocityConfiguration(VelocityConfig config, LanguageCatalog languages, boolean banner) {
    static VelocityConfiguration load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path legacy=directory.resolve("velocity.properties");
        boolean migrating=!Files.exists(directory.resolve("config.yml")) && Files.exists(legacy);
        Properties old=new Properties();
        if(migrating) {
            VelocityConfig.load(legacy); // Reject invalid legacy values before creating migration output.
            try(var reader=Files.newBufferedReader(legacy)){old.load(reader);}
            if(!Files.exists(directory.resolve("velocity.properties.legacy.bak")))
                Files.copy(legacy,directory.resolve("velocity.properties.legacy.bak"));
        }
        Map<String,String> builtIn=json(resource("languages/ru.json"));
        for(String locale:List.of("ru","en")) {
            Path target=directory.resolve("languages/"+locale+".json");
            if(!Files.exists(target)) {
                Map<String,String> messages=new LinkedHashMap<>(json(resource("languages/"+locale+".json")));
                if(migrating&&locale.equals("ru"))for(String key:old.stringPropertyNames())
                    if(key.startsWith("message."))messages.put(key.substring(8),old.getProperty(key));
                ConfigFiles.writeAtomic(target,new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(messages)+"\n");
            }
        }
        Map<String,String> merged=new LinkedHashMap<>();
        for(String file:List.of("connection.yml","attack.yml","premium.yml","config.yml")) {
            String source=resource("defaults/"+file);
            Map<String,String> defaults=yaml(source,file);
            Path path=directory.resolve(file);
            if(!Files.exists(path)) {
                if(migrating) {
                    // Replace values in the documented template without losing its comments.
                    for(String key:defaults.keySet())if(old.containsKey(key)) {
                        String value=new GsonBuilder().disableHtmlEscaping().create().toJson(old.getProperty(key));
                        source=source.replaceAll("(?m)^"+java.util.regex.Pattern.quote(key)+":.*$",java.util.regex.Matcher.quoteReplacement(key+": "+value));
                    }
                }
                ConfigFiles.writeAtomic(path,source);
            }
            Map<String,String> actual=yaml(Files.readString(path),file);
            for(String key:actual.keySet())if(!defaults.containsKey(key))
                throw new IllegalArgumentException("Unknown or misplaced setting '"+key+"' in "+file);
            merged.putAll(defaults);merged.putAll(actual);
        }
        String language=merged.remove("language-default");
        boolean detect=bool(merged.remove("language-auto-detect"),"language-auto-detect");
        boolean banner=bool(merged.remove("console-banner"),"console-banner");
        LanguageCatalog catalog=LanguageCatalog.load(directory.resolve("languages"),language,detect,builtIn);
        Properties values=new Properties();values.putAll(merged);
        catalog.templates(language).forEach((key,value)->values.setProperty("message."+key,value));
        return new VelocityConfiguration(VelocityConfig.parse(values,directory.toAbsolutePath()),catalog,banner);
    }
    private static boolean bool(String value,String key) {
        if(!Set.of("true","false").contains(value))throw new IllegalArgumentException(key+" must be true or false");
        return Boolean.parseBoolean(value);
    }
    private static Map<String,String> yaml(String source,String file) {
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);
        try {
            Object parsed=new Yaml(new SafeConstructor(options)).load(source);
            if(!(parsed instanceof Map<?,?> map))throw new IllegalArgumentException("Expected a YAML mapping in "+file);
            Map<String,String> result=new LinkedHashMap<>();flatten(map,"",result,file);return result;
        } catch(org.yaml.snakeyaml.error.YAMLException error) {throw new IllegalArgumentException("Invalid YAML in "+file,error);}
    }
    private static void flatten(Map<?,?> map,String prefix,Map<String,String> result,String file) {
        for(var entry:map.entrySet()) {
            if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException("Setting name must be text in "+file);
            String path=prefix+key;
            if(entry.getValue() instanceof Map<?,?> nested)flatten(nested,path+".",result,file);
            else if(entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean)
                result.put(path,entry.getValue().toString());
            else throw new IllegalArgumentException("Invalid setting: "+file+" / "+path);
        }
    }
    private static Map<String,String> json(String source) {
        return new GsonBuilder().create().fromJson(source,new TypeToken<Map<String,String>>(){}.getType());
    }
    private static String resource(String name)throws IOException {
        try(InputStream stream=VelocityConfiguration.class.getResourceAsStream("/"+name)) {
            if(stream==null)throw new IOException("Missing bundled configuration: "+name);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
