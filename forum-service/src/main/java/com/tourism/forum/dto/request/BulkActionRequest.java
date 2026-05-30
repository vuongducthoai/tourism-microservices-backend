package com.tourism.forum.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkActionRequest {

    @NotEmpty(message = "ids không được rỗng")
    private List<Integer> ids;

    /** approve | reject | hide | publish | delete */
    @NotNull(message = "action không được null")
    private String action;
}
