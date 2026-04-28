package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.entity.Location;
import com.tourism.tourcatalog.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LocationRepository extends JpaRepository<Location, Integer> {

    /**
     * Điểm khởi hành: Location được ít nhất 1 tour active dùng làm startLocation.
     * Dùng cho GET /api/locations/start-location
     */
    @Query("""
            SELECT DISTINCT l FROM Location l
            JOIN l.startPoint t
            WHERE t.status = true
              AND l.status = true
            """)
    List<Location> findDistinctStartLocations();

    /**
     * Điểm đến: Location được ít nhất 1 tour active dùng làm endLocation.
     * Dùng cho GET /api/locations/end-location
     */
    @Query("""
            SELECT DISTINCT l FROM Location l
            JOIN l.endPoint t
            WHERE t.status = true
              AND l.status = true
            """)
    List<Location> findDistinctEndLocations();

    /**
     * Locations theo vùng miền (NORTH / CENTRAL / SOUTH).
     * Dùng cho POST /api/locations/destinations-by-region
     */
    @Query("""
            SELECT l FROM Location l
            WHERE l.region = :region
              AND l.status = true
            """)
    List<Location> findByRegionActive(@Param("region") Region region);
}
