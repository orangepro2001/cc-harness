//go:build !(linux && (amd64 || arm64 || arm || 386 || riscv64))

package main

import (
	"errors"
	"os"
	"os/exec"
)

func setWinsize(f *os.File, cols, rows int) error {
	return errors.New("terminal is not supported on this platform")
}

func startPTY(cmd *exec.Cmd, cols, rows int) (*os.File, error) {
	return nil, errors.New("terminal is not supported on this platform")
}
