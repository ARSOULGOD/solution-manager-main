package codec

import (
	"fmt"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

// Explicit switches: an unknown value is an error, never a silent default.

func configViewStatusToPB(s msg.ConfigViewStatus) (pb.ConfigViewStatus, error) {
	switch s {
	case msg.ConfigViewOK:
		return pb.ConfigViewStatus_CONFIG_VIEW_STATUS_OK, nil
	case msg.ConfigViewRefused:
		return pb.ConfigViewStatus_CONFIG_VIEW_STATUS_REFUSED, nil
	}
	return 0, fmt.Errorf("codec: unknown config view status %q", s)
}

func configViewStatusFromPB(p pb.ConfigViewStatus) (msg.ConfigViewStatus, error) {
	switch p {
	case pb.ConfigViewStatus_CONFIG_VIEW_STATUS_OK:
		return msg.ConfigViewOK, nil
	case pb.ConfigViewStatus_CONFIG_VIEW_STATUS_REFUSED:
		return msg.ConfigViewRefused, nil
	}
	return "", fmt.Errorf("codec: unknown config view status %d", int32(p))
}

// configViewReasonToPB maps "" to UNSPECIFIED (an OK reply carries no reason).
func configViewReasonToPB(r msg.ConfigViewReason) (pb.ConfigViewReason, error) {
	switch r {
	case "":
		return pb.ConfigViewReason_CONFIG_VIEW_REASON_UNSPECIFIED, nil
	case msg.ConfigViewPlacementNotInLease:
		return pb.ConfigViewReason_CONFIG_VIEW_REASON_PLACEMENT_NOT_IN_LEASE, nil
	case msg.ConfigViewFileMissing:
		return pb.ConfigViewReason_CONFIG_VIEW_REASON_FILE_MISSING, nil
	case msg.ConfigViewFileUnreadable:
		return pb.ConfigViewReason_CONFIG_VIEW_REASON_FILE_UNREADABLE, nil
	case msg.ConfigViewFileTooLarge:
		return pb.ConfigViewReason_CONFIG_VIEW_REASON_FILE_TOO_LARGE, nil
	case msg.ConfigViewInvalidYAML:
		return pb.ConfigViewReason_CONFIG_VIEW_REASON_INVALID_YAML, nil
	case msg.ConfigViewNotPermitted:
		return pb.ConfigViewReason_CONFIG_VIEW_REASON_NOT_PERMITTED, nil
	case msg.ConfigViewTooManyKeys:
		return pb.ConfigViewReason_CONFIG_VIEW_REASON_TOO_MANY_KEYS, nil
	}
	return 0, fmt.Errorf("codec: unknown config view reason %q", r)
}

func configViewReasonFromPB(p pb.ConfigViewReason) (msg.ConfigViewReason, error) {
	switch p {
	case pb.ConfigViewReason_CONFIG_VIEW_REASON_UNSPECIFIED:
		return "", nil
	case pb.ConfigViewReason_CONFIG_VIEW_REASON_PLACEMENT_NOT_IN_LEASE:
		return msg.ConfigViewPlacementNotInLease, nil
	case pb.ConfigViewReason_CONFIG_VIEW_REASON_FILE_MISSING:
		return msg.ConfigViewFileMissing, nil
	case pb.ConfigViewReason_CONFIG_VIEW_REASON_FILE_UNREADABLE:
		return msg.ConfigViewFileUnreadable, nil
	case pb.ConfigViewReason_CONFIG_VIEW_REASON_FILE_TOO_LARGE:
		return msg.ConfigViewFileTooLarge, nil
	case pb.ConfigViewReason_CONFIG_VIEW_REASON_INVALID_YAML:
		return msg.ConfigViewInvalidYAML, nil
	case pb.ConfigViewReason_CONFIG_VIEW_REASON_NOT_PERMITTED:
		return msg.ConfigViewNotPermitted, nil
	case pb.ConfigViewReason_CONFIG_VIEW_REASON_TOO_MANY_KEYS:
		return msg.ConfigViewTooManyKeys, nil
	}
	return "", fmt.Errorf("codec: unknown config view reason %d", int32(p))
}

func configViewRequestToPB(r msg.ConfigViewRequest) *pb.ConfigViewRequest {
	return &pb.ConfigViewRequest{RequestId: r.RequestID, PlacementId: r.PlacementID, Allow: r.Allow, MaxBytes: r.MaxBytes}
}

func configViewRequestFromPB(p *pb.ConfigViewRequest) msg.ConfigViewRequest {
	return msg.ConfigViewRequest{RequestID: p.GetRequestId(), PlacementID: p.GetPlacementId(), Allow: p.GetAllow(), MaxBytes: p.GetMaxBytes()}
}

func configViewReplyToPB(r msg.ConfigViewReply) (*pb.ConfigViewReply, error) {
	st, err := configViewStatusToPB(r.Status)
	if err != nil {
		return nil, err
	}
	rs, err := configViewReasonToPB(r.Reason)
	if err != nil {
		return nil, err
	}
	var entries []*pb.ConfigEntry
	for _, e := range r.Entries {
		entries = append(entries, &pb.ConfigEntry{Path: e.Path, Value: e.Value})
	}
	return &pb.ConfigViewReply{RequestId: r.RequestID, Status: st, Entries: entries, HiddenCount: r.HiddenCount, Truncated: r.Truncated, Reason: rs}, nil
}

func configViewReplyFromPB(p *pb.ConfigViewReply) (msg.ConfigViewReply, error) {
	st, err := configViewStatusFromPB(p.GetStatus())
	if err != nil {
		return msg.ConfigViewReply{}, err
	}
	rs, err := configViewReasonFromPB(p.GetReason())
	if err != nil {
		return msg.ConfigViewReply{}, err
	}
	var entries []msg.ConfigEntry
	for _, e := range p.GetEntries() {
		entries = append(entries, msg.ConfigEntry{Path: e.GetPath(), Value: e.GetValue()})
	}
	return msg.ConfigViewReply{RequestID: p.GetRequestId(), Status: st, Entries: entries, HiddenCount: p.GetHiddenCount(), Truncated: p.GetTruncated(), Reason: rs}, nil
}
