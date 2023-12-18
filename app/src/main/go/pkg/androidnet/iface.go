package androidnet

/*
#include <ifaddrs.h>
#include <arpa/inet.h>
#include <net/if.h>
*/
import "C"
import (
	"fmt"
	"net"
	"strings"
	"syscall"
	"unsafe"
)

// Workaround for github.com/golang/go/issues/40569
// (Calling net.InterfaceAddrs() fails on Android SDK 30)
//
//
// This relies on getifaddrs, which requires SDK 24 (Android 7.0 Nougat)

func Interfaces() ([]Interface, error) {
	var addr *C.struct_ifaddrs
	ret, err := C.getifaddrs(&addr)
	if ret != 0 {
		return nil, fmt.Errorf("getifaddrs: %w", err.(syscall.Errno))
	}
	defer C.freeifaddrs(addr)

	interfaces := make(map[string]Interface)
	curr := addr

	var iface Interface
	for {
		if curr == nil {
			break
		}

		iface = makeInterface(curr)

		// getifaddrs() returns a separate list entry for AF_INET and AF_INET6 addresses, but the
		// other fields are identical. Merge into a single entry as net.Interfaces() does.
		if existing, ok := interfaces[iface.Name]; ok {
			iface.addrs = append(iface.addrs, existing.addrs...)
		}

		// Replaces (potentially) existing with merged version
		interfaces[iface.Name] = iface

		// Advance to next item in linked list
		curr = curr.ifa_next
	}

	s := make([]Interface, 0)
	for _, iface = range interfaces {
		s = append(s, iface)
	}
	return s, nil
}

type Interface struct {
	net.Interface
	addrs []net.Addr
}

func (i *Interface) Addrs() ([]net.Addr, error) {
	return i.addrs, nil
}

func (i *Interface) MulticastAddrs() ([]net.Addr, error) {
	return nil, fmt.Errorf("not implemented")
}

func InterfaceAddrs() ([]net.Addr, error) {
	interfaces, err := Interfaces()
	if err != nil {
		return nil, err
	}

	addrs := make([]net.Addr, 0)
	for _, iface := range interfaces {
		iAddrs, _ := iface.Addrs()
		addrs = append(addrs, iAddrs...)
	}
	return addrs, nil
}

func InterfaceByIndex(index int) (*Interface, error) {
	interfaces, err := Interfaces()
	if err != nil {
		return nil, err
	}

	for _, iface := range interfaces {
		if iface.Index == index {
			return &iface, nil
		}
	}

	return nil, fmt.Errorf("no such network interface")
}

func InterfaceByName(name string) (*Interface, error) {
	interfaces, err := Interfaces()
	if err != nil {
		return nil, err
	}

	for _, iface := range interfaces {
		if iface.Name == name {
			return &iface, nil
		}
	}

	return nil, fmt.Errorf("no such network interface")
}

func makeInterface(ifa *C.struct_ifaddrs) (i Interface) {
	i.Name = C.GoString(ifa.ifa_name)
	i.Flags = linkFlags(uint32(ifa.ifa_flags))
	i.Index = index(ifa)
	i.addrs = []net.Addr{}

	// TODO: getifaddrs doesn't supply this, but it could be fetched via SIOCGIFMTU if needed
	i.MTU = 0

	// HardwareAddr (MAC) is not populated -- this whole package is needed because Android
	// locked down RTM_GETLINK to prevent access to the MAC. The rest of the Interface fields
	// were collateral damage.

	// Not all interfaces have an addr (e.g. VPNs)
	if ifa.ifa_addr == nil {
		return
	}

	var ip string
	family := int(ifa.ifa_addr.sa_family)
	switch family {
	case syscall.AF_INET:
		ip = sockaddr4(ifa.ifa_addr)
	case syscall.AF_INET6:
		ip = sockaddr6(ifa.ifa_addr)
	default:
		// Unsupported address family, omit the addr
		return
	}

	// TODO: addr is incomplete. Populate netmask (ifa.ifa_netmask) and IPv6 Zone.
	addr := net.IPAddr{IP: net.ParseIP(ip)}
	i.addrs = []net.Addr{&addr}

	return
}

func index(ifa *C.struct_ifaddrs) int {
	return int(C.if_nametoindex(ifa.ifa_name))
}

func sockaddr4(sockaddr *C.struct_sockaddr) string {
	var buf [C.INET_ADDRSTRLEN]byte
	addr := (*C.struct_sockaddr_in)(unsafe.Pointer(sockaddr))
	C.inet_ntop(
		syscall.AF_INET,
		unsafe.Pointer(&addr.sin_addr),
		(*C.char)(unsafe.Pointer(&buf[0])),
		C.socklen_t(len(buf)),
	)
	return stripNULL(string(buf[:]))
}
func sockaddr6(sockaddr *C.struct_sockaddr) string {
	var buf [C.INET6_ADDRSTRLEN]byte
	addr := (*C.struct_sockaddr_in6)(unsafe.Pointer(sockaddr))
	C.inet_ntop(
		syscall.AF_INET6,
		unsafe.Pointer(&addr.sin6_addr),
		(*C.char)(unsafe.Pointer(&buf[0])),
		C.socklen_t(len(buf)),
	)
	return stripNULL(string(buf[:]))
}

func linkFlags(rawFlags uint32) net.Flags {
	var f net.Flags
	if rawFlags&syscall.IFF_UP != 0 {
		f |= net.FlagUp
	}
	if rawFlags&syscall.IFF_BROADCAST != 0 {
		f |= net.FlagBroadcast
	}
	if rawFlags&syscall.IFF_LOOPBACK != 0 {
		f |= net.FlagLoopback
	}
	if rawFlags&syscall.IFF_POINTOPOINT != 0 {
		f |= net.FlagPointToPoint
	}
	if rawFlags&syscall.IFF_MULTICAST != 0 {
		f |= net.FlagMulticast
	}
	return f
}

func stripNULL(s string) string {
	s, _, _ = strings.Cut(s, "\x00")
	return s
}
