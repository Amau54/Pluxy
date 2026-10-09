using System;
using System.Collections.Generic;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace Pluxy {
    internal static class LanDiscoveryTargets {
        [DllImport("iphlpapi.dll", ExactSpelling=true)]
        private static extern uint GetIpNetTable(IntPtr table, ref uint size, bool ordered);

        internal static bool SameSubnet(IPAddress local, IPAddress mask, IPAddress remote) {
            if(local.AddressFamily!=AddressFamily.InterNetwork || mask.AddressFamily!=AddressFamily.InterNetwork || remote.AddressFamily!=AddressFamily.InterNetwork) return false;
            var a=local.GetAddressBytes(); var m=mask.GetAddressBytes(); var b=remote.GetAddressBytes();
            if(b[0]==0 || b[0]==127 || b[0]>=224 || (b[0]==169 && b[1]==254)) return false;
            for(int i=0;i<4;i++) if((a[i]&m[i])!=(b[i]&m[i])) return false;
            bool network=true,broadcast=true;
            for(int i=0;i<4;i++) { network &= (b[i]&~m[i])==0; broadcast &= (b[i]|m[i])==255; }
            return !network && !broadcast && !local.Equals(remote);
        }

        internal static List<IPAddress> SubnetCandidates(IPAddress local, IPAddress mask) {
            var result=new List<IPAddress>();
            if(local.AddressFamily!=AddressFamily.InterNetwork || mask.AddressFamily!=AddressFamily.InterNetwork) return result;
            var a=local.GetAddressBytes(); var m=mask.GetAddressBytes();
            if(!(a[0]==10 || (a[0]==172 && a[1]>=16 && a[1]<=31) || (a[0]==192 && a[1]==168))) return result;
            uint ip=0,bits=0; for(int i=0;i<4;i++) { ip=(ip<<8)|a[i]; bits=(bits<<8)|m[i]; }
            uint hosts=~bits; if(bits==0 || (hosts&(hosts+1))!=0) return result;
            uint first=hosts<=511?(ip&bits):(ip&0xffffff00); uint count=hosts<=511?hosts+1:256;
            for(uint i=0;i<count;i++) {
                uint candidate=first+i;
                var remote=new IPAddress(new[]{(byte)(candidate>>24),(byte)(candidate>>16),(byte)(candidate>>8),(byte)candidate});
                if(SameSubnet(local,mask,remote)) result.Add(remote);
            }
            return result;
        }

        internal static List<IPAddress> UnicastTargets() {
            var result=CachedNeighbors();
            foreach(var adapter in NetworkInterface.GetAllNetworkInterfaces()) {
                if(adapter.OperationalStatus!=OperationalStatus.Up || adapter.NetworkInterfaceType==NetworkInterfaceType.Loopback || adapter.NetworkInterfaceType==NetworkInterfaceType.Tunnel) continue;
                try {
                    foreach(var local in adapter.GetIPProperties().UnicastAddresses) {
                        if(local.Address.AddressFamily!=AddressFamily.InterNetwork || local.IPv4Mask==null) continue;
                        foreach(var remote in SubnetCandidates(local.Address,local.IPv4Mask)) {
                            if(result.Count>=512) return result;
                            if(!result.Contains(remote)) result.Add(remote);
                        }
                    }
                } catch(NetworkInformationException) { }
            }
            return result;
        }

        // Read only the neighbor cache: no subnet sweep or Internet probe.
        internal static List<IPAddress> CachedNeighbors() {
            var result=new List<IPAddress>(); var subnets=new Dictionary<int,List<UnicastIPAddressInformation>>();
            foreach(var adapter in NetworkInterface.GetAllNetworkInterfaces()) {
                if(adapter.OperationalStatus!=OperationalStatus.Up || adapter.NetworkInterfaceType==NetworkInterfaceType.Loopback || adapter.NetworkInterfaceType==NetworkInterfaceType.Tunnel) continue;
                try {
                    var properties=adapter.GetIPProperties(); var ipv4=properties.GetIPv4Properties(); if(ipv4==null) continue;
                    var addresses=new List<UnicastIPAddressInformation>();
                    foreach(var address in properties.UnicastAddresses) if(address.Address.AddressFamily==AddressFamily.InterNetwork && address.IPv4Mask!=null) addresses.Add(address);
                    subnets[ipv4.Index]=addresses;
                } catch(NetworkInformationException) { }
            }
            uint size=0; if(GetIpNetTable(IntPtr.Zero,ref size,false)!=122 || size<4 || size>1024*1024) return result;
            var table=Marshal.AllocHGlobal((int)size);
            try {
                if(GetIpNetTable(table,ref size,false)!=0) return result;
                int count=Marshal.ReadInt32(table); if(count<0 || count>(size-4)/24) return result;
                for(int i=0;i<count && result.Count<64;i++) {
                    int offset=4+i*24; int type=Marshal.ReadInt32(table,offset+20);
                    if(type!=3 && type!=4) continue;
                    List<UnicastIPAddressInformation> addresses;
                    if(!subnets.TryGetValue(Marshal.ReadInt32(table,offset),out addresses)) continue;
                    var remote=new IPAddress(BitConverter.GetBytes(Marshal.ReadInt32(table,offset+16)));
                    foreach(var local in addresses) if(SameSubnet(local.Address,local.IPv4Mask,remote)) {
                        if(!result.Contains(remote)) result.Add(remote); break;
                    }
                }
            } finally { Marshal.FreeHGlobal(table); }
            return result;
        }
    }
}
