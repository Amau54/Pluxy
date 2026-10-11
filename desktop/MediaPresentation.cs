using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;

namespace Pluxy {
    internal static class MediaPresentation {
        private static readonly Regex Episode = new Regex(@"s\d{1,2}[ ._-]?e\d{1,2}|\b\d{1,2}x\d{2}\b",RegexOptions.IgnoreCase);
        private static readonly Regex Technical = new Regex(@"(?:^|[ \[-])(?:MULTI|VOSTFR|TRUEFRENCH|VFQ|VFF|VF2|2160p|1080[pi]|720p|480p|4K|UHD|WEB[ -]?(?:DL|RIP)|Blu[ -]?Ray|BDRip|HDTV|[hx][ .]?26[45]|HEVC|AV1|HDR10?\+?|DV|DOLBY[ -]?VISION|PROPER|REPACK|10[ -]?bits?)(?=$|[ \]()-])",RegexOptions.IgnoreCase);

        internal static string EpisodeTitle(IDictionary<string,object> item) {
            string title=Native.Text(item,"title");
            var marker=Episode.Match(title);
            if(marker.Success) title=title.Substring(marker.Index+marker.Length);
            title=Regex.Replace(title,@"[._]+|\s+"," ").Trim(' ','-');
            var technical=Technical.Match(title);
            if(technical.Success) title=title.Substring(0,technical.Index).Trim(' ','-','[');
            return title.Length>0?title:"Épisode "+Math.Max(1,(int)Native.Number(item,"episode"));
        }

        internal static string PlaybackTitle(IDictionary<string,object> item) {
            if(Native.Text(item,"kind")!="series") return Native.Text(item,"metadata_title",Native.Text(item,"title"));
            string series=Native.Text(item,"metadata_title",Native.Text(item,"series_name"));
            string title=EpisodeTitle(item), code=EpisodeCode(item);
            bool fallback=title=="Épisode "+Math.Max(1,(int)Native.Number(item,"episode"));
            return (series.Length>0?series+" · ":"")+code+(fallback?"":" · "+title);
        }

        internal static string EpisodeCode(IDictionary<string,object> item) {
            int season=(int)Native.Number(item,"season"),episode=Math.Max(1,(int)Native.Number(item,"episode"));
            return (item.ContainsKey("season") && item["season"]!=null?"Saison "+season+" - ":"")+"Épisode "+episode;
        }
    }
}
