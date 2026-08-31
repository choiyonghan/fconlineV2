package com.fconline.app.record.controller;

import com.fconline.app.record.facade.RecordFacade;
import com.fconline.app.record.dto.AssistChainResponse;
import com.fconline.app.record.dto.MatchShotsResponse;
import com.fconline.app.record.dto.MatchSquadEntryResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.PlayerGradeResponse;
import com.fconline.app.record.dto.RecentMatchResponse;
import com.fconline.app.record.dto.ShotHeatmapResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.domain.match.vo.MatchType;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/records")
public class RecordController {

    private final RecordFacade recordFacade;

    public RecordController(RecordFacade recordFacade) {
        this.recordFacade = recordFacade;
    }

    @GetMapping("/overall")
    public OverallRecordResponse getOverall(@RequestParam String ouid,
                                             @RequestParam MatchType matchType,
                                             @RequestParam(required = false) Long seasonId,
                                             @RequestParam(required = false) Long teamPeriodId) {
        return recordFacade.getOverallRecord(ouid, matchType, seasonId, teamPeriodId);
    }

    /**
     * 좌표 히트맵. goalsOnly=true면 득점한 슛만(기본값 false, 전체 슛).
     * opponentOuid를 지정하면 그 상대와의 경기만("상대별 전적" 펼침의 평균 득점 xG값 계산용).
     */
    @GetMapping("/shot-heatmap")
    public ShotHeatmapResponse getShotHeatmap(@RequestParam String ouid,
                                               @RequestParam MatchType matchType,
                                               @RequestParam(required = false) Long seasonId,
                                               @RequestParam(required = false) String opponentOuid,
                                               @RequestParam(required = false, defaultValue = "false") boolean goalsOnly,
                                               @RequestParam(required = false) Long teamPeriodId) {
        return recordFacade.getShotHeatmap(ouid, matchType, seasonId, opponentOuid, goalsOnly, teamPeriodId);
    }

    /**
     * xA(기대 어시스트) 히트맵 — 어시스트가 달린 슛(골 여부 무관)의 좌표 원시 목록. 프론트가
     * shot-heatmap과 똑같이 calcXg로 합산해서 "평균 어시 xA값"을 계산한다.
     * opponentOuid를 지정하면 그 상대와의 경기만("상대별 전적" 펼침의 평균 어시 xA 계산용).
     */
    @GetMapping("/assisted-shot-heatmap")
    public ShotHeatmapResponse getAssistedShotHeatmap(@RequestParam String ouid,
                                                        @RequestParam MatchType matchType,
                                                        @RequestParam(required = false) Long seasonId,
                                                        @RequestParam(required = false) String opponentOuid,
                                                        @RequestParam(required = false) Long teamPeriodId) {
        return recordFacade.getAssistedShotHeatmap(ouid, matchType, seasonId, opponentOuid, teamPeriodId);
    }

    /**
     * "실점 xG값"용 — 추적 대상 상대가 이 유저를 향해 쏜 슛 좌표(상대가 추적 대상인 매치만 포함).
     * opponentOuid를 지정하면 그 상대와의 경기만("상대별 전적" 펼침의 평균 실점 xG값 계산용).
     */
    @GetMapping("/conceded-shot-heatmap")
    public ShotHeatmapResponse getConcededShotHeatmap(@RequestParam String ouid,
                                                        @RequestParam MatchType matchType,
                                                        @RequestParam(required = false) Long seasonId,
                                                        @RequestParam(required = false) String opponentOuid,
                                                        @RequestParam(required = false) Long teamPeriodId) {
        return recordFacade.getConcededShotHeatmap(ouid, matchType, seasonId, opponentOuid, teamPeriodId);
    }

    /**
     * 매치 상세 모달용 — 특정 매치 1건의 슛 이벤트(내가 쏜 슛=득점 상세, 상대가 쏜 슛=실점 상세).
     * concededShots는 상대도 추적 대상이어야 채워진다(아니면 빈 목록).
     */
    @GetMapping("/match-shots")
    public MatchShotsResponse getMatchShots(@RequestParam String ouid,
                                             @RequestParam MatchType matchType,
                                             @RequestParam String matchId) {
        return recordFacade.getMatchShots(ouid, matchType, matchId);
    }

    /**
     * 매치 상세 모달의 MOM/Worst Player용 — 특정 매치 1건의 이 유저 스쿼드 전체(평점 포함).
     * Nexon API에 MOM 플래그가 없어 프론트가 이 rating을 비교해서 직접 뽑는다.
     */
    @GetMapping("/match-squad")
    public List<MatchSquadEntryResponse> getMatchSquad(@RequestParam String ouid,
                                                         @RequestParam MatchType matchType,
                                                         @RequestParam String matchId) {
        return recordFacade.getMatchSquad(ouid, matchType, matchId);
    }

    /**
     * 매치 상세 모달의 "상대 스탯 비교"용 — 특정 매치 1건, 이 ouid 관점의 팀 스탯 한 행
     * ("최근 경기" 목록 응답과 필드가 같다). 상대 ouid를 넣어 호출하면 상대 쪽 스탯이 나온다
     * (상대가 추적 대상이 아니면 400 — 프론트는 실패 시 "비교 데이터 없음"으로 처리한다).
     */
    @GetMapping("/match-stats")
    public RecentMatchResponse getMatchStats(@RequestParam String ouid,
                                              @RequestParam MatchType matchType,
                                              @RequestParam String matchId) {
        return recordFacade.getMatchStats(ouid, matchType, matchId);
    }

    /** 어시스트 체인 상위 목록(누가 누구에게 어시스트해서 득점했는지). */
    @GetMapping("/assist-chains")
    public List<AssistChainResponse> getAssistChains(@RequestParam String ouid,
                                                       @RequestParam MatchType matchType,
                                                       @RequestParam(required = false) Long seasonId,
                                                       @RequestParam(required = false) Integer limit,
                                                       @RequestParam(required = false) Long teamPeriodId) {
        return recordFacade.getAssistChains(ouid, matchType, seasonId, limit, teamPeriodId);
    }

    /**
     * 정렬 가능한 "전체 선수" 그리드, 최다 세이브 등 top-3 밖 통계용 — 사실상 전체 목록.
     * opponentOuid를 지정하면 그 상대와의 경기만 집계한다("상대별 전적" 행을 펼쳤을 때 씀).
     */
    @GetMapping("/players")
    public List<TopPlayerResponse> getAllPlayers(@RequestParam String ouid,
                                                  @RequestParam MatchType matchType,
                                                  @RequestParam(required = false) Long seasonId,
                                                  @RequestParam(required = false) String opponentOuid,
                                                  @RequestParam(required = false) Long teamPeriodId) {
        return recordFacade.getAllPlayers(ouid, matchType, seasonId, opponentOuid, teamPeriodId);
    }

    /**
     * spId별 카드 강화 단계(0~11강, 가장 최근 매치 기준) — 선수 이름이 나오는 화면에서 공용으로
     * 붙여 쓰는 조회. 슛을 한 번도 안 쏜 선수는 목록에서 빠진다(shoot_events 기반).
     */
    @GetMapping("/player-grades")
    public List<PlayerGradeResponse> getPlayerGrades(@RequestParam String ouid,
                                                       @RequestParam MatchType matchType,
                                                       @RequestParam(required = false) Long seasonId,
                                                       @RequestParam(required = false) Long teamPeriodId) {
        return recordFacade.getPlayerGrades(ouid, matchType, seasonId, teamPeriodId);
    }

    /** 상대 무관, 이 유저의 진짜 최신 경기 목록(매치 날짜 내림차순, 더보기 페이징). */
    @GetMapping("/recent-matches")
    public Page<RecentMatchResponse> getRecentMatches(@RequestParam String ouid,
                                                        @RequestParam MatchType matchType,
                                                        @RequestParam(required = false) Long seasonId,
                                                        @RequestParam(required = false) Long teamPeriodId,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return recordFacade.getRecentMatches(ouid, matchType, seasonId, teamPeriodId, pageable);
    }
}
