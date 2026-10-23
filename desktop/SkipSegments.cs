using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace Pluxy {
    internal sealed class SkipSegment {
        public string kind;
        public long startMs,endMs;
    }
    internal sealed class SkipMarkers {
        public long introStartMs;
        public long? introEndMs,creditsStartMs,creditsEndMs;
        public List<SkipSegment> Segments(long duration) {
            var result=new List<SkipSegment>();
            if(introEndMs.HasValue && introStartMs>=0 && introEndMs>introStartMs && introEndMs<duration)
                result.Add(new SkipSegment {kind="intro",startMs=introStartMs,endMs=introEndMs.Value});
            long end=creditsEndMs ?? duration;
            if(creditsStartMs.HasValue && creditsStartMs>=0 && end>creditsStartMs && end<=duration)
                result.Add(new SkipSegment {kind="credits",startMs=creditsStartMs.Value,endMs=end});
            return result;
        }
    }
    internal static class SkipSegments {
        internal static bool SupportsKind(string kind) { return kind=="series"; }
        internal static bool IsFinalCredits(SkipSegment segment,long duration) {
            return segment!=null && segment.kind=="credits" && duration>0 && segment.startMs>=duration/2 &&
                segment.endMs<=duration && segment.endMs>=duration-500;
        }
        internal static object[] Chapters(object[] values,double duration) {
            var sorted=(values ?? new object[0]).Select(Native.Object).Where(row=>Native.Number(row,"time",-1)>=0)
                .OrderBy(row=>Native.Number(row,"time")).Take(300).ToArray();
            return sorted.Select((row,index)=>(object)new Dictionary<string,object>{{"title",Native.Text(row,"title")},
                {"start_ms",Native.Number(row,"time")*1000},{"end_ms",(index+1<sorted.Length?Native.Number(sorted[index+1],"time"):duration)*1000}}).ToArray();
        }
        internal static List<SkipSegment> Detect(object[] chapters,long duration) {
            var result=new List<SkipSegment>();
            foreach(var value in (chapters ?? new object[0]).Take(300)) {
                var row=Native.Object(value);
                string title=Regex.Replace(Native.Text(row,"title").Normalize(NormalizationForm.FormD),"\\p{M}","").ToLowerInvariant();
                title=Regex.Replace(Regex.Replace(title,"[^a-z0-9]+"," ").Trim(),"^\\d+ ","");
                string compact=title.Replace(" ","");
                string kind=new[]{"intro","introduction","opening","openingcredits","generiquedebut","generiquededebut","opencreditstart","openingcreditstart","openingcreditsstart","introstart"}.Contains(compact)?"intro":
                    new[]{"credits","endcredits","closingcredits","ending","outro","generiquefin","generiquedefin","endcreditstart","endcreditsstart","creditsstart"}.Contains(compact)?"credits":null;
                double a=Native.Number(row,"start_ms",-1),b=Native.Number(row,"end_ms",-1);
                if(kind==null || double.IsNaN(a) || double.IsInfinity(a) || double.IsNaN(b) || double.IsInfinity(b) || a<0 || b<=a || b>duration) continue;
                long start=(long)a,end=(long)b;
                if(kind=="intro" && (start>900000 || end>duration/2 || end-start>240000)) continue;
                if(kind=="credits" && (start<duration/2 || end-start>1200000)) continue;
                result.Add(new SkipSegment {kind=kind,startMs=start,endMs=end});
            }
            return result;
        }
        internal static List<SkipSegment> Merge(List<SkipSegment> detected,List<SkipSegment> manual) {
            return manual.Concat(detected.Where(auto=>!manual.Any(own=>own.kind==auto.kind))).ToList();
        }
        internal static List<SkipSegment> Resolve(List<SkipSegment> detected,List<SkipSegment> own,List<SkipSegment> season) {
            var precise=Merge(detected,own);
            return precise.Concat(season.Where(fallback=>!precise.Any(segment=>segment.kind==fallback.kind))).ToList();
        }
        internal static SkipSegment Active(List<SkipSegment> segments,long position) {
            return segments.FirstOrDefault(segment=>position>=segment.startMs && position<segment.endMs-500);
        }
    }
    internal sealed class SkipMarkerStore {
        private static readonly object gate=new object();
        private readonly string path;
        internal SkipMarkerStore(string path=null) { this.path=path ?? Path.Combine(Native.Data,"skip-markers.json"); }
        private static string Key(string server,string value) {
            using(var hash=System.Security.Cryptography.SHA256.Create()) return BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(server.TrimEnd('/')+"\n"+value))).Replace("-","").ToLowerInvariant();
        }
        private string MediaKey(string server,IDictionary<string,object> item) { return Key(server,"media:"+Native.Text(item,"id")); }
        private string SeasonKey(string server,IDictionary<string,object> item) {
            return CanShare(item)?Key(server,"season:"+Native.Text(item,"source_id")+":"+Native.Text(item,"series_key")+":"+Native.Text(item,"season")):null;
        }
        internal bool CanShare(IDictionary<string,object> item) { return Native.Text(item,"kind")=="series" && Native.Text(item,"series_key")!="" && Native.Text(item,"season")!=""; }
        private Dictionary<string,SkipMarkers> Read() {
            if(!File.Exists(path)) return new Dictionary<string,SkipMarkers>();
            try { if(new FileInfo(path).Length>1024*1024) throw new IOException("Repères trop volumineux.");
                return Native.Json.Deserialize<Dictionary<string,SkipMarkers>>(File.ReadAllText(path)) ?? new Dictionary<string,SkipMarkers>();
            } catch(Exception error) { Native.Log(error); File.Move(path,path+".invalid-"+Guid.NewGuid().ToString("N")); return new Dictionary<string,SkipMarkers>(); }
        }
        private SkipMarkers At(Dictionary<string,SkipMarkers> values,string key) { SkipMarkers value; return key!=null && values.TryGetValue(key,out value) && value!=null?value:new SkipMarkers(); }
        internal SkipMarkers Own(string server,IDictionary<string,object> item) { lock(gate) return At(Read(),MediaKey(server,item)); }
        internal SkipMarkers Effective(string server,IDictionary<string,object> item) {
            lock(gate) {
                var values=Read(); var own=At(values,MediaKey(server,item)); var season=At(values,SeasonKey(server,item));
                if(!own.introEndMs.HasValue && own.introStartMs==0) { own.introStartMs=season.introStartMs; own.introEndMs=season.introEndMs; }
                return own;
            }
        }
        internal bool HasSeason(string server,IDictionary<string,object> item) { lock(gate) return At(Read(),SeasonKey(server,item)).introEndMs.HasValue; }
        private void Write(string key,SkipMarkers marker) {
            if(key==null) return;
            lock(gate) { var values=Read(); if(marker==null) values.Remove(key); else { if(!values.ContainsKey(key) && values.Count>=2000) values.Remove(values.Keys.First()); values[key]=marker; } Native.WriteAtomic(path,Native.Json.Serialize(values)); }
        }
        internal void Save(string server,IDictionary<string,object> item,SkipMarkers markers) { Write(MediaKey(server,item),markers); }
        internal void Share(string server,IDictionary<string,object> item,SkipMarkers markers) { Write(SeasonKey(server,item),new SkipMarkers {introStartMs=markers.introStartMs,introEndMs=markers.introEndMs}); }
        internal void Clear(string server,IDictionary<string,object> item) { Write(MediaKey(server,item),null); }
        internal void ClearSeason(string server,IDictionary<string,object> item) { Write(SeasonKey(server,item),null); }
    }
}
