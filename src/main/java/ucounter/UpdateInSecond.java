package ucounter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by isinelnikov on 20.04.17.
 * Инкапсулирует количество обновлений в секунду
 */
public class UpdateInSecond {
    private final long second;

    private final AtomicInteger count;

    public long getSecond() {
        return second;
    }

    public int getCount() {
        return count.get();
    }

    int incrementCount() {
        return count.incrementAndGet();
    }

    UpdateInSecond(long second) {
        this.second = second;
        count = new AtomicInteger(1);
    }

    @Override
    public String toString() {
        return second + " -> " + count;
    }
}