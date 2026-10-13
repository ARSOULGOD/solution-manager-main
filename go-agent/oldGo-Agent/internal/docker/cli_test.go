package docker

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"testing"
)

type stubCtrl struct {
	startErr   error
	stopErr    error
	restartErr error
	statusErr  error
	info       Info
	ops        []string
}

func (s *stubCtrl) Start(_ context.Context, name string) error {
	s.ops = append(s.ops, "start:"+name)
	return s.startErr
}
func (s *stubCtrl) Stop(_ context.Context, name string) error {
	s.ops = append(s.ops, "stop:"+name)
	return s.stopErr
}
func (s *stubCtrl) Restart(_ context.Context, name string) error {
	s.ops = append(s.ops, "restart:"+name)
	return s.restartErr
}
func (s *stubCtrl) Status(_ context.Context, name string) (Info, error) {
	s.ops = append(s.ops, "status:"+name)
	if s.statusErr != nil {
		return Info{}, s.statusErr
	}
	info := s.info
	if info.Name == "" {
		info.Name = name
	}
	return info, nil
}

func TestRunCLI_Start(t *testing.T) {
	var buf bytes.Buffer
	ctrl := &stubCtrl{}
	if err := RunCLI(context.Background(), ctrl, []string{"start", "java1"}, &buf); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "started java1") {
		t.Fatalf("out=%q", buf.String())
	}
}

func TestRunCLI_Status(t *testing.T) {
	var buf bytes.Buffer
	ctrl := &stubCtrl{info: Info{Name: "java2", ID: "x", State: "running", Status: "running"}}
	if err := RunCLI(context.Background(), ctrl, []string{"status", "java2"}, &buf); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "state=running") {
		t.Fatalf("out=%q", buf.String())
	}
}

func TestRunCLI_MissingName(t *testing.T) {
	err := RunCLI(context.Background(), &stubCtrl{}, []string{"stop"}, &bytes.Buffer{})
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestRunCLI_NotFound(t *testing.T) {
	err := RunCLI(context.Background(), &stubCtrl{startErr: ErrNotFound}, []string{"start", "nope"}, &bytes.Buffer{})
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("got %v", err)
	}
}

func TestIsLifecycleCommand(t *testing.T) {
	if !IsLifecycleCommand("start") || IsLifecycleCommand("-data-dir") {
		t.Fatal("unexpected")
	}
}
