using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;

namespace Pluxy {
    internal static class SettingsBackup {
        private const long Limit=64*1024*1024;
        private static System.Web.Script.Serialization.JavaScriptSerializer BackupJson { get { return new System.Web.Script.Serialization.JavaScriptSerializer { MaxJsonLength=(int)Limit }; } }
        internal static bool Allowed(string name) {
            return name=="desktop.json" || name=="config.json" || name==".pluxy_watch.json" || name=="family-profiles.json" || name=="progress-outbox.json" || name=="skip-markers.json" || Regex.IsMatch(name,"^profiles/[0-9a-f]{32}/[.]pluxy_watch[.]json$");
        }
        private static bool Secret(string key) { return key=="tmdb_api_key" || key=="omdb_api_key"; }
        internal static object WithoutSecrets(object value) {
            var dict=value as IDictionary<string,object>;
            if(dict!=null) { var result=new Dictionary<string,object>(); foreach(var pair in dict) if(!Secret(pair.Key)) result[pair.Key]=WithoutSecrets(pair.Value); return result; }
            var array=value as object[];
            if(array!=null) { for(int i=0;i<array.Length;i++) array[i]=WithoutSecrets(array[i]); }
            return value;
        }
        private static void KeepSecrets(IDictionary<string,object> imported,IDictionary<string,object> current) {
            foreach(var pair in current) {
                if(Secret(pair.Key)) imported[pair.Key]=pair.Value;
                else if(imported.ContainsKey(pair.Key) && imported[pair.Key] is IDictionary<string,object> && pair.Value is IDictionary<string,object>) KeepSecrets(Native.Object(imported[pair.Key]),Native.Object(pair.Value));
            }
        }
        public static void Export(string target) {
            if(target.StartsWith(Native.Data+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)) throw new IOException("Choisissez un dossier extérieur aux réglages de Pluxy.");
            Directory.CreateDirectory(Native.Data);
            var temporary=target+"."+Guid.NewGuid().ToString("N")+".tmp";
            try {
                using(var zip=ZipFile.Open(temporary,ZipArchiveMode.Create)) {
                    long total=0;
                    var paths=new List<string>(Directory.GetFiles(Native.Data,"*.json",SearchOption.TopDirectoryOnly));
                    var profiles=Path.Combine(Native.Data,"profiles");
                    if(Directory.Exists(profiles)) foreach(var folder in Directory.GetDirectories(profiles)) { var watch=Path.Combine(folder,".pluxy_watch.json"); if(File.Exists(watch)) paths.Add(watch); }
                    foreach(var path in paths) {
                        string name=path.Substring(Native.Data.Length+1).Replace('\\','/'); if(!Allowed(name)) continue;
                        if(new FileInfo(path).Length>Limit) throw new IOException("Un fichier de réglages dépasse 64 Mo.");
                        var value=BackupJson.DeserializeObject(File.ReadAllText(path));
                        var bytes=Encoding.UTF8.GetBytes(BackupJson.Serialize(WithoutSecrets(value))); total+=bytes.Length;
                        if(total>Limit) throw new IOException("La sauvegarde dépasse 64 Mo.");
                        using(var stream=zip.CreateEntry(name).Open()) stream.Write(bytes,0,bytes.Length);
                    }
                }
                if(File.Exists(target)) File.Replace(temporary,target,null); else File.Move(temporary,target);
            } finally { if(File.Exists(temporary)) File.Delete(temporary); }
        }
        public static Dictionary<string,string> Read(string source) {
            var values=new Dictionary<string,string>(); long total=0;
            using(var zip=ZipFile.OpenRead(source)) {
                if(zip.Entries.Count>32) throw new IOException("Trop de fichiers dans la sauvegarde.");
                foreach(var entry in zip.Entries) {
                    if(!Allowed(entry.FullName) || values.ContainsKey(entry.FullName) || entry.Length>Limit) throw new IOException("Sauvegarde invalide.");
                    total+=entry.Length; if(total>Limit) throw new IOException("Sauvegarde trop volumineuse.");
                    using(var input=entry.Open()) using(var output=new MemoryStream()) {
                        var buffer=new byte[8192]; int count;
                        while((count=input.Read(buffer,0,buffer.Length))>0) { if(output.Length+count>entry.Length) throw new IOException("Sauvegarde incohérente."); output.Write(buffer,0,count); }
                        var json=Encoding.UTF8.GetString(output.ToArray()); var value=BackupJson.DeserializeObject(json);
                        bool array=entry.FullName=="progress-outbox.json";
                        if(array ? !(value is object[]) : !(value is IDictionary<string,object>)) throw new IOException("Structure de sauvegarde invalide : "+entry.FullName);
                        if(entry.FullName=="desktop.json") Native.Json.Deserialize<Preferences>(json);
                        if(entry.FullName=="config.json") {
                            var path=Path.Combine(Native.Data,"config.json");
                            if(File.Exists(path)) KeepSecrets(Native.Object(value),Native.Object(Native.Json.DeserializeObject(File.ReadAllText(path))));
                        }
                        values[entry.FullName]=BackupJson.Serialize(value);
                    }
                }
            }
            if(!values.ContainsKey("desktop.json") && !values.ContainsKey("config.json")) throw new IOException("Aucun réglage Pluxy dans cette sauvegarde.");
            return values;
        }
        public static void Restore(Dictionary<string,string> values) {
            var recovery=Path.Combine(Native.Data,"before-restore-"+DateTime.UtcNow.ToString("yyyyMMdd-HHmmss")+"-"+Guid.NewGuid().ToString("N")+".zip");
            var temporary=Path.Combine(Path.GetTempPath(),"Pluxy-backup-"+Guid.NewGuid().ToString("N")+".zip");
            Export(temporary); File.Move(temporary,recovery);
            var original=new Dictionary<string,string>();
            foreach(var pair in values) { if(!Allowed(pair.Key)) throw new IOException("Réglage invalide."); var path=Path.Combine(Native.Data,pair.Key.Replace('/',Path.DirectorySeparatorChar)); original[path]=File.Exists(path)?File.ReadAllText(path):null; }
            try { foreach(var pair in values) Native.WriteAtomic(Path.Combine(Native.Data,pair.Key.Replace('/',Path.DirectorySeparatorChar)),pair.Value); }
            catch { foreach(var pair in original) try { if(pair.Value==null) File.Delete(pair.Key); else Native.WriteAtomic(pair.Key,pair.Value); } catch(Exception error) { Native.Log(error); } throw; }
        }
    }
}
