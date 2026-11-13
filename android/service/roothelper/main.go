// Command flroot runs as root and hands FlClash a TUN descriptor, so the Core
// can take traffic without VpnService. Usage: flroot <abstract-socket> <ifname>
package main

import (
	"fmt"
	"os"
	"syscall"
	"unsafe"
)

const (
	tunSetIff = 0x400454ca
	iffTun    = 0x0001
	iffNoPi   = 0x1000
)

type ifReq struct {
	name  [16]byte
	flags uint16
	_     [22]byte
}

func openTun(name string) (int, error) {
	var lastErr error
	for _, path := range []string{"/dev/tun", "/dev/net/tun"} {
		fd, err := syscall.Open(path, syscall.O_RDWR|syscall.O_CLOEXEC, 0)
		if err != nil {
			lastErr = err
			continue
		}
		var req ifReq
		copy(req.name[:15], name)
		req.flags = iffTun | iffNoPi
		if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, uintptr(fd), tunSetIff, uintptr(unsafe.Pointer(&req))); errno != 0 {
			syscall.Close(fd)
			return -1, fmt.Errorf("TUNSETIFF %s: %v", path, errno)
		}
		return fd, nil
	}
	return -1, lastErr
}

func send(socket string, fd int) error {
	s, err := syscall.Socket(syscall.AF_UNIX, syscall.SOCK_STREAM|syscall.SOCK_CLOEXEC, 0)
	if err != nil {
		return err
	}
	defer syscall.Close(s)
	if err := syscall.Connect(s, &syscall.SockaddrUnix{Name: "@" + socket}); err != nil {
		return fmt.Errorf("connect: %v", err)
	}
	return syscall.Sendmsg(s, []byte{1}, syscall.UnixRights(fd), nil, 0)
}

func main() {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: flroot <socket> <ifname>")
		os.Exit(2)
	}
	fd, err := openTun(os.Args[2])
	if err == nil {
		err = send(os.Args[1], fd)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "flroot:", err)
		os.Exit(1)
	}
}
