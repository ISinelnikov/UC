package ucounter;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 *  Created by isinelnikov on 20.04.17.
 *  Регистрирует новые события в системе
 */
public class UpdateCounter {
    private static volatile UpdateCounter instance;

    /**
     * Возвращает экземпляр счетчика обновлений
     * @return
     */
    public static UpdateCounter getInstance() {
        UpdateCounter localInstance = instance;
        if (localInstance == null) {
            synchronized (UpdateCounter.class) {
                localInstance = instance;
                if (localInstance == null) {
                    instance = localInstance = new UpdateCounter();
                }
            }
        }
        return localInstance;
    }

    private UpdateCounter () {
        secondInterval = new ConcurrentLinkedDeque<>();
    }

    /**
     * Время последнего обновления минутного LIFO
     */
    private volatile long lastUpdateTime = 0;

    /**
     * LIFO интервала
     * Интервалом считается промежуток от некоторого x до x + 59
     */
    private ConcurrentLinkedDeque<UpdateInSecond> currentInterval;

    /**
     * LIFO существования приложения
     * Хранит информацию о предыдущих минутных интервалах
     */
    private ConcurrentLinkedDeque<ConcurrentLinkedDeque<UpdateInSecond>> secondInterval;

    /**
     * Проверяет попадание переданной секунды в текущий интервал
     * Если переданная секунда наступила после текущего минутного интервала
     * скидывает предыдущий LIFO в LIFO приложения и заводит новый интервал
     * @param currentTime время вызова проверки
     */
    private void replacementMinuteInterval(long currentTime) {
        synchronized (UpdateCounter.class) {
            if (currentTime - lastUpdateTime > 60) {
                if (currentInterval != null) {
                    secondInterval.offer(currentInterval);
                }
                currentInterval = new ConcurrentLinkedDeque<>();
                lastUpdateTime = currentTime;
            }
        }
    }

    /**
     * Учет некоторого события (допущение отсутствия метаинформации о событиях)
     */
    public void update() {
        // Время регистрации события в системе
        long currentTime = System.currentTimeMillis() / 1000;

        // Проверяем время последнего обновления интервала
        if (currentTime - lastUpdateTime > 60) {
            replacementMinuteInterval(currentTime);
        }

        // Проверяем необходимость добавления нового значения в интервал
        if (currentInterval.peekFirst() == null || currentInterval.peekFirst().getSecond() < currentTime) {
            // Избежание параллельного добавления одного значения
            synchronized (UpdateCounter.class) {
                // Check
                if (currentInterval.peekFirst() == null || currentInterval.peekFirst().getSecond() < currentTime) {
                    currentInterval.addFirst(new UpdateInSecond(currentTime));
                } else {
                    currentInterval.peekFirst().incrementCount();
                }
            }
        } else {
            currentInterval.peekFirst().incrementCount();
        }
    }

    /**
     * Возвращает количество операций за последнюю минуту
     * @return
     */
    public long numberOfUpdatesLastMinute() {
        long methodCallTime = System.currentTimeMillis() / 1000;

        long luTime = lastUpdateTime;

        long result = 0;

        final long[] lastTime = { 0 };

        Iterator<UpdateInSecond> updateInSecondIterator = null;

        synchronized (UpdateCounter.class) {
            if (intervalCheck(methodCallTime, luTime, 120)) {
                return result;
            } else {
                if (secondInterval.peekLast() != null) {
                    updateInSecondIterator = secondInterval.peekLast().iterator();
                }
            }
        }

        // Суммируем последние значения из прошлого интервала
        if (updateInSecondIterator != null) {
            updateInSecondIterator.forEachRemaining(second -> {
                if (methodCallTime - second.getSecond() < 59) {
                    lastTime[0] += second.getCount();
                }
            });
        }

        // События из текущего интервала
        long firstTime = currentIntervalValue(methodCallTime, luTime, 60);

        result = firstTime + lastTime[0];

        return result;
    }

    /**
     * Количество операций за последние n часов
     * @param n
     * @return
     */
    private long numberOfUpdateLastHours(int n) {
        long methodCallTime = System.currentTimeMillis() / 1000;

        long luTime = lastUpdateTime;

        int secondInN = n * 3600;

        Iterator<ConcurrentLinkedDeque<UpdateInSecond>> dequeIterator;
        long firstTime;

        synchronized (UpdateCounter.class) {
            if (intervalCheck(methodCallTime, luTime, secondInN)) {
                return 0;
            } else {
                dequeIterator = secondInterval.descendingIterator();

                // События не сброшенные в очередь
                firstTime = currentIntervalValue(methodCallTime, luTime, secondInN);
            }
        }
        // События за прошедшее время
        final long[] lastTime = { 0 };

        //  Iterators weakly consistent
        secondInterval.descendingIterator().forEachRemaining((minutes) -> {
            if (methodCallTime - minutes.peekLast().getSecond() < secondInN - 1) {
                minutes.descendingIterator().forEachRemaining((second) -> {
                    if (methodCallTime - second.getSecond() < secondInN - 1) {
                        lastTime[0] += second.getCount();
                    }
                });
            } else if (methodCallTime - minutes.peekLast().getSecond() < secondInN + 59) {
                // Проверяем последние секунды следующего интервала после последней успешной проверки
                minutes.forEach(second -> {
                    if (methodCallTime - second.getSecond() < secondInN) lastTime[0] += second.getCount();
                });
            }
        });

        return firstTime + lastTime[0];
    }

    /**
     * Возвращает сумму событий из последнего временного интервала до момента вызова метода
     * @param methodCallTime
     * @param luTime
     * @param interval минута/час/сутки
     * @return
     */
    private long currentIntervalValue(long methodCallTime, long luTime, int interval) {
        final long[] result = { 0 };
        Iterator<UpdateInSecond> updateInSecondIterator = null;
        synchronized (UpdateCounter.class) {
            if (methodCallTime - luTime < interval) updateInSecondIterator = currentInterval.descendingIterator();
        }
        if (updateInSecondIterator != null) {
            // Раскручиваем очередь с верхнего элемента
            updateInSecondIterator.forEachRemaining(second -> {
                if (methodCallTime - second.getSecond() >= 0) {
                    result[0] += second.getCount();
                }
            });
        }
        return result[0];
    }

    /**
     * Проверка обновления последнего интервала
     * @param methodCallTime время вызова метода
     * @param luTime время создания последнего интервала
     * @param secondInN
     * @return false - если с последнего интервала прошло больше secondInN - 1 или последнего интервала не создавалось
     */
    private boolean intervalCheck(long methodCallTime, long luTime, int secondInN) {
        return (luTime == 0 || methodCallTime - luTime > secondInN - 1);
    }

    /**
     * Количество событий за последний час
     * @return
     */
    public long numberOfUpdateLastHour() {
        return numberOfUpdateLastHours(1);
    }

    /**
     * Количество событий за последние 24 часа
     * @return
     */
    public long numberOfUpdateLastDay() {
        return numberOfUpdateLastHours(24);
    }
}