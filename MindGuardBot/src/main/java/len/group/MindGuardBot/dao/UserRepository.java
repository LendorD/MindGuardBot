package len.group.MindGuardBot.dao;

import len.group.MindGuardBot.models.Task;
import len.group.MindGuardBot.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    User findByChatId(Long chatId);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.tasks WHERE u.chatId = :chatId")
    User findByChatIdWithTasks(@Param("chatId") Long chatId);

}

