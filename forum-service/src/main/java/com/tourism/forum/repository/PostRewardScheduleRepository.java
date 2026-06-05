package com.tourism.forum.repository;

import com.tourism.forum.entity.PostRewardSchedule;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PostRewardScheduleRepository extends JpaRepository<PostRewardSchedule, Long> {

    boolean existsByPostId(Integer postId);

    /** Số bài đã được xếp lịch thưởng trong ngày — quota tối đa 3 bài/ngày được thưởng. */
    long countByUserIdAndCreatedAtGreaterThanEqual(Integer userId, LocalDateTime start);

    /** Các lịch đã đến hạn (đủ 24h) chờ xử lý. */
    List<PostRewardSchedule> findByStatusAndEligibleAtBefore(
            PostRewardSchedule.ScheduleStatus status, LocalDateTime now, Pageable pageable);
}
