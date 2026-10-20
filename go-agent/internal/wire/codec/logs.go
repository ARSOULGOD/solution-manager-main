package codec

import (
	"fmt"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

// ---- MI logs (plan 016) ----

// unavailableToPB maps explicitly (no name-string lookup). An unknown value is
// an error, never silently "available": a page that claims lines exist when the
// agent meant otherwise would mislead the gateway.
func unavailableToPB(r msg.UnavailableReason) (pb.UnavailableReason, error) {
	switch r {
	case msg.UnavailableNone:
		return pb.UnavailableReason_UNAVAILABLE_REASON_UNSPECIFIED, nil
	case msg.UnavailableNotCollecting:
		return pb.UnavailableReason_UNAVAILABLE_REASON_NOT_COLLECTING, nil
	case msg.UnavailableContainerMissing:
		return pb.UnavailableReason_UNAVAILABLE_REASON_CONTAINER_MISSING, nil
	case msg.UnavailableBusy:
		return pb.UnavailableReason_UNAVAILABLE_REASON_BUSY, nil
	case msg.UnavailablePlacementNotLease:
		return pb.UnavailableReason_UNAVAILABLE_REASON_PLACEMENT_NOT_IN_LEASE, nil
	}
	return 0, fmt.Errorf("codec: unknown unavailable reason %q", string(r))
}

// unavailableFromPB is the inverse. A value this build does not know (a newer
// peer) is an error rather than a guess; the caller drops the message.
func unavailableFromPB(p pb.UnavailableReason) (msg.UnavailableReason, error) {
	switch p {
	case pb.UnavailableReason_UNAVAILABLE_REASON_UNSPECIFIED:
		return msg.UnavailableNone, nil
	case pb.UnavailableReason_UNAVAILABLE_REASON_NOT_COLLECTING:
		return msg.UnavailableNotCollecting, nil
	case pb.UnavailableReason_UNAVAILABLE_REASON_CONTAINER_MISSING:
		return msg.UnavailableContainerMissing, nil
	case pb.UnavailableReason_UNAVAILABLE_REASON_BUSY:
		return msg.UnavailableBusy, nil
	case pb.UnavailableReason_UNAVAILABLE_REASON_PLACEMENT_NOT_IN_LEASE:
		return msg.UnavailablePlacementNotLease, nil
	}
	return "", fmt.Errorf("codec: unknown pb unavailable reason %d", int32(p))
}

func logLinesToPB(ls []msg.LogLine) []*pb.LogLine {
	var out []*pb.LogLine
	for _, l := range ls {
		out = append(out, &pb.LogLine{Ts: ts(l.TS), Stream: l.Stream, Text: l.Text, Truncated: l.Truncated})
	}
	return out
}

func logLinesFromPB(ps []*pb.LogLine) []msg.LogLine {
	var out []msg.LogLine
	for _, l := range ps {
		out = append(out, msg.LogLine{TS: tsFrom(l.GetTs()), Stream: l.GetStream(), Text: l.GetText(), Truncated: l.GetTruncated()})
	}
	return out
}

func logGapsToPB(gs []msg.LogGap) []*pb.LogGap {
	var out []*pb.LogGap
	for _, g := range gs {
		out = append(out, &pb.LogGap{From: ts(g.From), To: ts(g.To), Reason: g.Reason})
	}
	return out
}

func logGapsFromPB(ps []*pb.LogGap) []msg.LogGap {
	var out []msg.LogGap
	for _, g := range ps {
		out = append(out, msg.LogGap{From: tsFrom(g.GetFrom()), To: tsFrom(g.GetTo()), Reason: g.GetReason()})
	}
	return out
}

func logRetentionFromPB(p *pb.LogRetention) msg.LogRetention {
	return msg.LogRetention{Envelope: envFromPB(p.GetEnvelope()), PlacementRef: p.GetPlacementRef(), MaxBytes: p.GetMaxBytes()}
}

func logRequestToPB(r msg.LogRequest) *pb.LogRequest {
	out := &pb.LogRequest{Envelope: envToPB(r.Envelope), RequestId: r.RequestID, PlacementRef: r.PlacementRef}
	switch {
	case r.Page != nil:
		out.Mode = &pb.LogRequest_Page{Page: &pb.LogPageQuery{Limit: r.Page.Limit, After: tsPtr(r.Page.After), Before: tsPtr(r.Page.Before)}}
	case r.Export != nil:
		out.Mode = &pb.LogRequest_Export{Export: &pb.LogExportQuery{FromSeq: r.Export.FromSeq}}
	}
	return out
}

func logRequestFromPB(p *pb.LogRequest) msg.LogRequest {
	out := msg.LogRequest{Envelope: envFromPB(p.GetEnvelope()), RequestID: p.GetRequestId(), PlacementRef: p.GetPlacementRef()}
	if q := p.GetPage(); q != nil {
		out.Page = &msg.LogPageQuery{Limit: q.GetLimit(), After: tsPtrFrom(q.After), Before: tsPtrFrom(q.Before)}
	}
	if q := p.GetExport(); q != nil {
		out.Export = &msg.LogExportQuery{FromSeq: q.GetFromSeq()}
	}
	return out
}

// EncodeLogUp encodes an agent → gateway Logs-stream message.
func EncodeLogUp(m msg.LogsOutbound) (*pb.LogUp, error) {
	switch v := m.(type) {
	case msg.LogPage:
		un, err := unavailableToPB(v.Unavailable)
		if err != nil {
			return nil, err
		}
		return &pb.LogUp{Msg: &pb.LogUp_LogPage{LogPage: &pb.LogPage{
			Envelope: envToPB(v.Envelope), RequestId: v.RequestID, Lines: logLinesToPB(v.Lines), Gaps: logGapsToPB(v.Gaps), Unavailable: un}}}, nil
	case msg.LogChunk:
		return &pb.LogUp{Msg: &pb.LogUp_LogChunk{LogChunk: &pb.LogChunk{
			Envelope: envToPB(v.Envelope), RequestId: v.RequestID, Seq: v.Seq, Lines: logLinesToPB(v.Lines), Gaps: logGapsToPB(v.Gaps), Final: v.Final}}}, nil
	}
	return nil, fmt.Errorf("codec: unknown logs message %T", m)
}

// DecodeLogUp is the gateway-side inverse of EncodeLogUp (fakegw, tests and any
// in-process loopback).
func DecodeLogUp(p *pb.LogUp) (msg.LogsOutbound, error) { return logUpFromPB(p) }

// EncodeLogDown encodes a gateway → agent Logs-stream message; the inverse of
// DecodeLogDown.
func EncodeLogDown(m msg.LogsInbound) (*pb.LogDown, error) {
	switch v := m.(type) {
	case msg.LogCredit:
		return &pb.LogDown{Msg: &pb.LogDown_LogCredit{LogCredit: &pb.LogCredit{RequestId: v.RequestID, CreditsGranted: v.CreditsGranted}}}, nil
	case msg.LogCancel:
		return &pb.LogDown{Msg: &pb.LogDown_LogCancel{LogCancel: &pb.LogCancel{RequestId: v.RequestID}}}, nil
	}
	return nil, fmt.Errorf("codec: unknown logs message %T", m)
}

// logUpFromPB is the inverse of EncodeLogUp.
func logUpFromPB(p *pb.LogUp) (msg.LogsOutbound, error) {
	switch v := p.GetMsg().(type) {
	case *pb.LogUp_LogPage:
		g := v.LogPage
		un, err := unavailableFromPB(g.GetUnavailable())
		if err != nil {
			return nil, err
		}
		return msg.LogPage{Envelope: envFromPB(g.GetEnvelope()), RequestID: g.GetRequestId(), Lines: logLinesFromPB(g.GetLines()), Gaps: logGapsFromPB(g.GetGaps()), Unavailable: un}, nil
	case *pb.LogUp_LogChunk:
		c := v.LogChunk
		return msg.LogChunk{Envelope: envFromPB(c.GetEnvelope()), RequestID: c.GetRequestId(), Seq: c.GetSeq(), Lines: logLinesFromPB(c.GetLines()), Gaps: logGapsFromPB(c.GetGaps()), Final: c.GetFinal()}, nil
	}
	return nil, fmt.Errorf("codec: LogUp carries nothing")
}

// DecodeLogDown decodes a gateway → agent Logs-stream message. An unknown or
// empty message is an error, never silently dropped.
func DecodeLogDown(p *pb.LogDown) (msg.LogsInbound, error) {
	switch v := p.GetMsg().(type) {
	case *pb.LogDown_LogCredit:
		return msg.LogCredit{RequestID: v.LogCredit.GetRequestId(), CreditsGranted: v.LogCredit.GetCreditsGranted()}, nil
	case *pb.LogDown_LogCancel:
		return msg.LogCancel{RequestID: v.LogCancel.GetRequestId()}, nil
	}
	return nil, fmt.Errorf("codec: unknown LogDown message %T", p.GetMsg())
}
