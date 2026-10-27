package com.example.webhook.repo;

import com.example.webhook.domain.Event;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {
    Optional<Event> findByEndpointIdAndIdempotencyKey(Long endpointId, String key);
    List<Event> findByStatusAndNextAttemptAtBefore(String status, Instant time);
    List<Event> findByStatusOrderByDeadAtDesc(String status, Pageable pageable);

    List<Event> findTop500ByStatusAndEndpointIdOrderByIdAsc(String status, Long endpointId);

    @Modifying
    @Transactional
    @Query("""
    update Event e
       set e.status = 'PENDING',
           e.attempts = 0,
           e.nextAttemptAt = :now,
           e.deadReason = null,
           e.deadAt = null,
           e.replayCount = coalesce(e.replayCount, 0) + 1
     where e.id = :id and e.status = 'DEAD'
    """)
    int revive(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("""
    update Event e
       set e.status = 'DELIVERING', e.lockedUntil = :lease
     where e.id = :id and e.status = 'PENDING'
    """)
    int claim(@Param("id") Long id, @Param("lease") Instant lease);

    @Query(value = """
    select id from event
     where (status = 'PENDING' and next_attempt_at < :cutoff)
        or (status = 'DELIVERING' and locked_until < :now)
     order by id
     limit :batch
       for update skip locked
    """, nativeQuery = true)
    List<Long> lockStuck(@Param("cutoff") Instant cutoff,
                         @Param("now") Instant now,
                         @Param("batch") int batch);

    @Modifying
    @Transactional
    @Query("""
    update Event e
       set e.status = 'PENDING', e.lockedUntil = null, e.nextAttemptAt = :now
     where e.id in :ids
    """)
    int resetAndBump(@Param("ids") List<Long> ids, @Param("now") Instant now);

    // imports: org.springframework.data.domain.Pageable (you already have it),
//          org.springframework.data.jpa.repository.Query

    List<Event> findAllByOrderByIdDesc(Pageable pageable);

    List<Event> findByStatusOrderByIdDesc(String status, Pageable pageable);

    @Query("select e.status as status, count(e) as total from Event e group by e.status")
    List<StatusCount> countByStatus();
}
