using System;

namespace Pluxy {
    internal static class SeekAcceleration {
        private static readonly long[] ends={1200,2500,5000,8000,12000,18000,long.MaxValue};
        private static readonly long[] rates={10000,20000,40000,80000,160000,320000,640000};
        public static double Distance(long elapsed) {
            long remaining=Math.Max(0,elapsed-400),cursor=400; double distance=10;
            for(int i=0;i<ends.Length && remaining>0;i++) { long used=Math.Min(remaining,ends[i]-cursor); distance+=used*rates[i]/1000000.0; remaining-=used; cursor=ends[i]; }
            return distance;
        }
        public static string Speed(long elapsed) { if(elapsed<400) return "Précis"; for(int i=0;i<ends.Length;i++) if(elapsed<ends[i]) return "×"+(1<<i); return "×64"; }
    }
}
