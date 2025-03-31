package len.group.MindGuardBot.dao;

import len.group.MindGuardBot.models.Task;
import len.group.MindGuardBot.models.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.awt.image.Raster;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByUserAndIsCompletedFalse(User user);
    List<Task> findByUserAndDeadlineBefore(User user, LocalDateTime now);
    List<Task> getAllByUser(User user);

    @EntityGraph(attributePaths = {"user"})
    Optional<Task> findById(Long id);

}