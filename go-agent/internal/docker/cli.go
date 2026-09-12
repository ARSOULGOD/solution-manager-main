package docker

import (
	"context"
	"fmt"
	"io"
	"strings"
)

// RunCLI dispatches start|stop|restart|status <name> against ctrl.
// args is the argv after the program name (e.g. ["start","java1"]).
func RunCLI(ctx context.Context, ctrl Controller, args []string, out io.Writer) error {
	if len(args) < 1 {
		return fmt.Errorf("usage: sm-agent <start|stop|restart|status> <container-name>")
	}
	verb := args[0]
	if len(args) < 2 || strings.TrimSpace(args[1]) == "" {
		return fmt.Errorf("usage: sm-agent %s <container-name>", verb)
	}
	name := args[1]

	switch verb {
	case "start":
		if err := ctrl.Start(ctx, name); err != nil {
			return err
		}
		fmt.Fprintf(out, "started %s\n", name)
		return nil
	case "stop":
		if err := ctrl.Stop(ctx, name); err != nil {
			return err
		}
		fmt.Fprintf(out, "stopped %s\n", name)
		return nil
	case "restart":
		if err := ctrl.Restart(ctx, name); err != nil {
			return err
		}
		fmt.Fprintf(out, "restarted %s\n", name)
		return nil
	case "status":
		info, err := ctrl.Status(ctx, name)
		if err != nil {
			return err
		}
		fmt.Fprintf(out, "name=%s id=%s state=%s status=%s\n", info.Name, info.ID, info.State, info.Status)
		return nil
	default:
		return fmt.Errorf("unknown command %q (want start|stop|restart|status)", verb)
	}
}

// IsLifecycleCommand reports whether arg is a container lifecycle subcommand.
func IsLifecycleCommand(arg string) bool {
	switch arg {
	case "start", "stop", "restart", "status":
		return true
	default:
		return false
	}
}
