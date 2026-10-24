//go:build linux && (amd64 || arm64 || arm || 386 || riscv64)

package main

import (
	"fmt"
	"os"
	"os/exec"
	"syscall"
	"unsafe"
)

// Linux ioctl numbers for the architectures above (asm-generic).
const (
	ioctlTIOCGPTN   = 0x80045430
	ioctlTIOCSPTLCK = 0x40045431
	ioctlTIOCSWINSZ = 0x5414
)

func ioctl(fd uintptr, req uintptr, arg unsafe.Pointer) error {
	if _, _, e := syscall.Syscall(syscall.SYS_IOCTL, fd, req, uintptr(arg)); e != 0 {
		return e
	}
	return nil
}

// setWinsize tells the terminal (and so its foreground process) its new size.
func setWinsize(f *os.File, cols, rows int) error {
	ws := struct{ rows, cols, x, y uint16 }{uint16(rows), uint16(cols), 0, 0}
	return ioctl(f.Fd(), ioctlTIOCSWINSZ, unsafe.Pointer(&ws))
}

// startPTY runs cmd on a fresh pseudo-terminal of the given size and returns the master side.
// Only the standard library is used: /dev/ptmx plus three ioctls.
func startPTY(cmd *exec.Cmd, cols, rows int) (*os.File, error) {
	master, err := os.OpenFile("/dev/ptmx", os.O_RDWR|syscall.O_NOCTTY, 0)
	if err != nil {
		return nil, err
	}
	fail := func(err error) (*os.File, error) {
		master.Close()
		return nil, err
	}
	var unlock int32
	if err := ioctl(master.Fd(), ioctlTIOCSPTLCK, unsafe.Pointer(&unlock)); err != nil {
		return fail(fmt.Errorf("unlockpt: %w", err))
	}
	var n uint32
	if err := ioctl(master.Fd(), ioctlTIOCGPTN, unsafe.Pointer(&n)); err != nil {
		return fail(fmt.Errorf("ptsname: %w", err))
	}
	slave, err := os.OpenFile(fmt.Sprintf("/dev/pts/%d", n), os.O_RDWR|syscall.O_NOCTTY, 0)
	if err != nil {
		return fail(err)
	}
	defer slave.Close()
	if err := setWinsize(slave, cols, rows); err != nil {
		return fail(err)
	}
	cmd.Stdin, cmd.Stdout, cmd.Stderr = slave, slave, slave
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true, Setctty: true} // child fd 0 becomes its controlling tty
	if err := cmd.Start(); err != nil {
		return fail(err)
	}
	return master, nil
}
