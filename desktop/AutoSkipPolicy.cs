using System.Collections.Generic;

namespace Pluxy {
    internal sealed class AutoSkipPolicy {
        private string owner="";
        private readonly HashSet<string> handled=new HashSet<string>();
        internal void Mark(string id,string kind) {
            if(owner!=id) { owner=id; handled.Clear(); }
            handled.Add(kind);
        }
        internal long? Target(string id,SkipSegment segment,bool enabled,bool canSkip) {
            if(owner!=id) { owner=id; handled.Clear(); }
            if(!enabled || !canSkip || segment==null || (segment.kind!="intro" && segment.kind!="credits") ||
                segment.startMs<0 || segment.endMs<=segment.startMs || handled.Contains(segment.kind)) return null;
            Mark(id,segment.kind);
            return segment.endMs;
        }
        internal static int ProbeDelay(int attempt) { return attempt<10?6000:attempt<30?15000:30000; }
    }
}
