package len.group.MindGuardBot.helper;

import lombok.Data;
import org.springframework.stereotype.Component;

@Component
@Data
public class ScheduleSession {

    public enum ScheduleStep {
        NONE,
        WAITING_FOR_SCHEDULE,
        WAITING_FOR_TASKS
    }


    private ScheduleStep step = ScheduleStep.NONE;
    private String rawSchedule;
    private String rawTasks;
}
