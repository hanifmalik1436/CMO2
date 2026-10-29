package com.cmo2.auth.team;

import com.cmo2.auth.team.TeamDtos.ChangeRoleRequest;
import com.cmo2.auth.team.TeamDtos.InviteRequest;
import com.cmo2.auth.team.TeamDtos.MemberResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/team")
@Tag(name = "Team")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    @Operation(summary = "List team members")
    public List<MemberResponse> list() {
        return teamService.list();
    }

    @PostMapping("/invite")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Invite a team member by email (consumes a seat)")
    public MemberResponse invite(@Valid @RequestBody InviteRequest request) {
        return teamService.invite(request);
    }

    @PatchMapping("/{userId}/role")
    @Operation(summary = "Change a member's role (owner only)")
    public MemberResponse changeRole(@PathVariable Long userId, @Valid @RequestBody ChangeRoleRequest request) {
        return teamService.changeRole(userId, request.role());
    }

    @PostMapping("/{userId}/deactivate")
    @Operation(summary = "Deactivate a team member")
    public MemberResponse deactivate(@PathVariable Long userId) {
        return teamService.deactivate(userId);
    }

    @PostMapping("/{userId}/reactivate")
    @Operation(summary = "Reactivate a deactivated team member")
    public MemberResponse reactivate(@PathVariable Long userId) {
        return teamService.reactivate(userId);
    }
}
