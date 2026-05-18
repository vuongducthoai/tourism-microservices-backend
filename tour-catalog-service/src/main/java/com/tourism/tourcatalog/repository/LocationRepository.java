package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.entity.Location;
import com.tourism.tourcatalog.entity.Region;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LocationRepository extends JpaRepository<Location, Integer> {

    @Query("""
            SELECT DISTINCT l FROM Location l
            JOIN l.startPoint t
            WHERE t.status = true
              AND l.status = true
            """)
    List<Location> findDistinctStartLocations();

    @Query("""
            SELECT DISTINCT l FROM Location l
            JOIN l.endPoint t
            WHERE t.status = true
              AND l.status = true
            """)
    List<Location> findDistinctEndLocations();

    @Query("""
            SELECT l FROM Location l
            WHERE l.region = :region
              AND l.status = true
            """)
    List<Location> findByRegionActive(@Param("region") Region region);

    Optional<Location> findByAirportCode(String airportCode);

    // --- Admin queries ---

    boolean existsByName(String name);

    @Query("SELECT COUNT(l) > 0 FROM Location l WHERE l.name = :name AND l.locationID != :excludeId")
    boolean existsByNameAndNotId(@Param("name") String name, @Param("excludeId") Integer excludeId);

    boolean existsBySlug(String slug);

    @Query("SELECT COUNT(l) > 0 FROM Location l WHERE l.slug = :slug AND l.locationID != :excludeId")
    boolean existsBySlugAndNotId(@Param("slug") String slug, @Param("excludeId") Integer excludeId);

    @Query("SELECT l FROM Location l WHERE " +
            "(:search IS NULL OR :search = '' OR " +
            " LOWER(l.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            " LOWER(l.slug) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            " LOWER(l.airportCode) LIKE LOWER(CONCAT('%', :search, '%'))) " +
            "AND (:region IS NULL OR l.region = :region)")
    Page<Location> searchLocations(
            @Param("search") String search,
            @Param("region") Region region,
            Pageable pageable
    );

    @Query("SELECT l FROM Location l WHERE l.airportCode IS NOT NULL AND l.airportCode <> ''")
    Page<Location> getAllAirportNational(Pageable pageable);

    @Query("SELECT COUNT(t) FROM Tour t WHERE t.startLocation.locationID = :locationId")
    Long countToursAsStartPoint(@Param("locationId") Integer locationId);

    @Query("SELECT COUNT(t) FROM Tour t WHERE t.endLocation.locationID = :locationId")
    Long countToursAsEndPoint(@Param("locationId") Integer locationId);
}
