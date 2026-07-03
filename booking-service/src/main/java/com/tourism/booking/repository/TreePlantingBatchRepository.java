package com.tourism.booking.repository;

import com.tourism.booking.entity.TreePlantingBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TreePlantingBatchRepository extends JpaRepository<TreePlantingBatch, Long> {

    List<TreePlantingBatch> findAllByOrderByPlantedDateDesc();
}
