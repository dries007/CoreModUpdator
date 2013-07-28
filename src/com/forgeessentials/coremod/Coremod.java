package com.forgeessentials.coremod;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import net.minecraft.launchwrapper.LaunchClassLoader;

import org.apache.commons.io.FileUtils;

import argo.jdom.JdomParser;
import argo.jdom.JsonNode;
import argo.jdom.JsonRootNode;
import argo.jdom.JsonStringNode;
import argo.saj.InvalidSyntaxException;

import com.forgeessentials.coremod.Module.ModuleFile;
import com.forgeessentials.coremod.dependencies.IDependency;
import com.forgeessentials.coremod.install.Main;
import com.google.common.base.Strings;

import cpw.mods.fml.relauncher.IFMLCallHook;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

/**
 * Main class, does all the real work. Look in {@link}Data to change URLs and stuff. (c) Copyright Dries007.net 2013 Written for ForgeEssentials, but might be useful for others.
 * 
 * @author Dries007
 */
@IFMLLoadingPlugin.Name(Data.NAME)
@IFMLLoadingPlugin.MCVersion(Data.MC_VERSION)
public class Coremod implements IFMLLoadingPlugin, IFMLCallHook
{
    public static final JdomParser JSON_PARSER = new JdomParser();
    public static boolean online;
    
    JsonRootNode root;
    
    /*
     * Map with all modules
     */
    HashMap<String, Module> moduleMap = new HashMap<String, Module>();
    
    //HashMap<String, Boolean> modulesMap = new HashMap<String, Boolean>();
    //HashMap<String, URL> toDownload = new HashMap<String, URL>();
    // Map of all the normal libs we want key = filename, value = hash
    //HashMap<String, IDependency> libsmap = new HashMap<String, IDependency>();
    // Sets for all ASM classes and ATs They get added later
    //HashSet<String> ASMClasses = new HashSet<String>();
    //HashSet<String> ATFiles = new HashSet<String>();
    
    public Void call() throws IOException
    {
        try
        {
            root = JSON_PARSER.parse(new InputStreamReader(new URL(Data.JSONURL).openStream()));
            online = true;
            
            /*
             * Version check
             */
            if (!root.getNode("CoreMod").getStringValue(Data.MC_VERSION).equals(Data.VERSION))
            {
                System.out.println("[" + Data.NAME + "] ##############################################################");
                System.out.println("[" + Data.NAME + "] ##### WARNING: The version you are using is out of date. #####");
                System.out.println("[" + Data.NAME + "] #####      This might result in issues. Update now!      #####");
                System.out.println("[" + Data.NAME + "] ##############################################################");
            }
        }
        catch (IOException e)
        {
            online = false;
            System.out.println("[" + Data.NAME + "] JSON offline? Check manually: " + Data.JSONURL);
            System.out.println("[" + Data.NAME + "] ##############################################################");
            System.out.println("[" + Data.NAME + "] #####       WARNING: The update URL is unavailable.      #####");
            System.out.println("[" + Data.NAME + "] #####           Only classloading will be done!          #####");
            System.out.println("[" + Data.NAME + "] ##############################################################");
        }
        catch (InvalidSyntaxException e)
        {
            online = false;
            System.out.println("[" + Data.NAME + "] Invalid JSON at target? Check manually: " + Data.JSONURL);
            System.out.println("[" + Data.NAME + "] ##############################################################");
            System.out.println("[" + Data.NAME + "] #####         WARNING: The update URL is corrupt.        #####");
            System.out.println("[" + Data.NAME + "] #####           Only classloading will be done!          #####");
            System.out.println("[" + Data.NAME + "] ##############################################################");
        }
        
        Main.setup();

        // We need a valid JSON for first boot.
        if (Main.firstRun && !online)
        {
            System.out.println("[" + Data.NAME + "] I can't do a first run when the data server is offline. Sorry!");
            Runtime.getRuntime().exit(1);
        }
        
        // Status message
        if (Main.firstRun) System.out.println("[" + Data.NAME + "] Doing a full first run.");
        else if (!Main.autoUpdate) System.out.println("[" + Data.NAME + "] You are NOT using autoupdate. We will only check dependencies and classload.");
        
        Main.properties.setProperty("firstRun", "false");
        
        if (online)
        {
            HashSet<File> wantedModuleFiles = new HashSet<File>();
            JsonNode modules = root.getNode("modules");
            for (JsonStringNode key : modules.getFields().keySet())
            {
                String moduleName = key.toString();
                Module module = new Module(moduleName);
                
                if (!Main.properties.containsKey("module." + moduleName)) Main.properties.put("module." + moduleName, "true");
                module.wanted = Boolean.parseBoolean(Main.properties.getProperty("module." + moduleName));
                
                /*
                 * Add files the JSON sais we need
                 */
                for (JsonNode fileNode : modules.getNode(key).getArrayNode(Data.MC_VERSION))
                {
                    File f = new File(Main.modulesFolder, fileNode.getStringValue("file"));
                    if (module.wanted) wantedModuleFiles.add(f);
                    module.files.add(new ModuleFile(f, new URL (Data.BASEURL + fileNode.getStringValue("url")), fileNode.getStringValue("hash")));
                }
                
                /*
                 * Check to see if said files exist
                 */
                module.checkJarFiles();
                
                /*
                 * Parse the modules jar files for interesting things
                 */
                module.parceJarFiles();
                
                moduleMap.put(module.name, module);
                
                /*
                 * Removing all non needed module files
                 */
                for (File file : Main.modulesFolder.listFiles())
                {
                    if (!wantedModuleFiles.contains(file)) file.delete();
                }
            }
        }
        else
        {
            /*
             * No checks for anything.
             */
            for (File file : Main.modulesFolder.listFiles())
            {
                if (!file.getName().endsWith(".jar")) file.delete();
                else
                {
                    Module m = new Module(file);
                    m.parceJarFiles();
                    moduleMap.put(m.name, m);
                }
            }
            
            classloadAll();
        }
        
        Main.saveProperties();
        return null;
    }

    /**
     * Returns nested dependencies
     * @param dependency
     * @return
     */
    public static Map<? extends String, ? extends IDependency> getDependencies(IDependency dependency)
    {
        HashMap<String, IDependency> map = new HashMap<String, IDependency>();
        
        for (IDependency nd : dependency.getTransitiveDependencies())
        {
            map.put(nd.getFileName(), nd);
            if (dependency.getTransitiveDependencies() != null && !dependency.getTransitiveDependencies().isEmpty()) map.putAll(getDependencies(nd));
        }
        
        return map;
    }
    
    /**
     * Gets all modules from JSON.
     * Looks in config to see which modules we want.
     * Downloads modules we want but don't have or downloads updated versions.
     * Removes modules we don't want.
     */
    public void getModules()
    {
        Main.comments += "\n# Modules" + "\n#      Default: true for all modules" + "\n#      Use this to change wich modules you want." + "\n#      Warning, If you set this to false, the module file will be removed.";
        
        /*
         * Modules In config
         */
        JsonNode modules = root.getNode("versions").getNode(Data.MC_VERSION);
        for (JsonStringNode module : modules.getFields().keySet())
        {
            String filename = null;
            URL url = null;
            try
            {
                List<JsonNode> list = modules.getNode(module.getText()).getArrayNode(Main.branch);
                filename = list.get(0).getText();
                url = new URL(Data.BASEURL + list.get(1).getText());
            }
            catch (Exception e)
            {
                // don't need to print or warn, is checked below.
            }
            if (!Strings.isNullOrEmpty(filename))
            {
                String name = "modules." + module.getText();
                if (!Main.properties.containsKey(name)) Main.properties.setProperty(name, "true");
                modulesMap.put(filename, Boolean.parseBoolean(Main.properties.getProperty(name)));
                toDownload.put(filename, url);
            }
        }
        
        /*
         * Modules In folder
         */
        for (File file : Main.modulesFolder.listFiles())
        {
            if (!modulesMap.containsKey(file.getName()))
            {
                file.delete();
            }
            else if (!modulesMap.get(file.getName()))
            {
                file.delete();
            }
            else
            {
                toDownload.remove(file.getName());
            }
        }
        
        /*
         * Downloading modules
         */
        for (String name : toDownload.keySet())
        {
            try
            {
                System.out.println("[" + Data.NAME + "] Downloading module " + name);
                FileUtils.copyURLToFile(toDownload.get(name), new File(Main.modulesFolder, name));
            }
            catch (Exception e)
            {}
        }
    }
    
    public void getDependencies() throws Exception
    {
        /*
         * Check all current libs
         */
        HashSet<String> usedLibs = new HashSet<String>();
        for (IDependency dependency : libsmap.values())
        {
            File file = new File(Main.libsFolder, dependency.getFileName());
            if (file.exists())
            {
                /*
                 * Checksum check 1
                 */
                if (!getChecksum(file).equals(dependency.getHash()))
                {
                    System.out.println("[" + Data.NAME + "] Lib " + dependency.getFileName() + " had wrong hash " + dependency.getHash() + " != " + getChecksum(file));
                    file.delete();
                }
                else
                {
                    /*
                     * All is good, next!
                     */
                    usedLibs.add(file.getName());
                    continue;
                }
            }
            if (!file.exists())
            {
                System.out.println("[" + Data.NAME + "] Downloading lib " + dependency.getFileName() + " from " + dependency.getDownloadURL());
                FileUtils.copyURLToFile(dependency.getDownloadURL(), file);
                /*
                 * Checksum check 2
                 */
                if (!getChecksum(file).equals(dependency.getHash()))
                {
                    System.out.println("[" + Data.NAME + "] Was not able to download " + dependency.getFileName() + " from " + dependency.getDownloadURL() + " with hash " + dependency.getHash() + ". We got hash " + getChecksum(file));
                    throw new RuntimeException();
                }
                /*
                 * Downloaded fine. Next!
                 */
                usedLibs.add(file.getName());
            }
        }
        
        /*
         * Remove not needed libs
         */
        for (File file : Main.libsFolder.listFiles())
        {
            if (!usedLibs.contains(file.getName()))
            {
                file.delete();
                System.out.println("[" + Data.NAME + "] Removing not needed lib " + file.getName());
            }
        }
    }
    
    /**
     * Classloads all of the things!
     * @throws MalformedURLException
     */
    public void classloadAll() throws MalformedURLException
    {
        for (Module m : moduleMap.values())
        {
            System.out.println("[" + Data.NAME + "] Module " + m.name + " adds:");
            
            for (String fileName : m.dependecies.keySet())
            {
                System.out.println("[" + Data.NAME + "] Dependency: " + fileName);
                Data.classLoader.addURL(new File(Main.libsFolder, fileName).toURI().toURL());
            }
            
            for (ModuleFile mf : m.files)
            {
                System.out.println("[" + Data.NAME + "] Module file: " + mf.file.getName());
                Data.classLoader.addURL(mf.file.toURI().toURL());
            }
            
            for (String asmclass : m.ASMClasses)
            {
                System.out.println("[" + Data.NAME + "] ASM class: " + asmclass);
                Data.classLoader.registerTransformer(asmclass);
            }
            
            for (String at : m.ATFiles)
            {
                System.out.println("[" + Data.NAME + "] AT: " + at);
                CustomAT.addTransformerMap(at);
            }
        }
    }
    
    public static String getChecksum(File file)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            FileInputStream fis = new FileInputStream(file);
            byte[] dataBytes = new byte[1024];
            
            int nread = 0;
            
            while ((nread = fis.read(dataBytes)) != -1)
            {
                md.update(dataBytes, 0, nread);
            }
            
            byte[] mdbytes = md.digest();
            
            // convert the byte to hex format
            StringBuffer sb = new StringBuffer("");
            for (int i = 0; i < mdbytes.length; i++)
            {
                sb.append(Integer.toString((mdbytes[i] & 0xff) + 0x100, 16).substring(1));
            }
            fis.close();
            
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            e.printStackTrace();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        
        return null;
    }
    
    public void injectData(Map<String, Object> data)
    {
        if (data.containsKey("runtimeDeobfuscationEnabled") && data.get("runtimeDeobfuscationEnabled") != null)
        {
            Data.debug = !(Boolean) data.get("runtimeDeobfuscationEnabled");
        }
        
        if (data.containsKey("classLoader") && data.get("classLoader") != null)
        {
            Data.classLoader = (LaunchClassLoader) data.get("classLoader");
        }
    }
    
    @Override
    @Deprecated
    public String[] getLibraryRequestClass()
    {
        return null;
    }
    
    @Override
    public String[] getASMTransformerClass()
    {
        return Data.ASMCLASSES;
    }
    
    @Override
    public String getModContainerClass()
    {
        return null;
    }
    
    @Override
    public String getSetupClass()
    {
        return this.getClass().getName();
    }
}