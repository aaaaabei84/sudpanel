package com.sudpanel.api;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 保留 Java regex/find 语义；在匹配器读取输入时检查可回收的本线程期限，不依赖取消后台线程。 */
public final class SafeRegex {
    public static final int MAX_PATTERN_CHARS = 4096;
    public static final long MATCH_LIMIT_MS = 200;
    public static final long JUDGEMENT_LIMIT_MS = 1000;
    private SafeRegex() { }

    public record Result(boolean matched, String error) { }

    public static long judgementDeadline() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JUDGEMENT_LIMIT_MS);
    }

    public static void validate(String expression) {
        compile(expression);
    }

    private static Pattern compile(String expression) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("正则表达式不能为空");
        if (expression.length() > MAX_PATTERN_CHARS)
            throw new IllegalArgumentException("正则表达式最多 " + MAX_PATTERN_CHARS + " 字符");
        try { return Pattern.compile(expression); }
        catch (PatternSyntaxException invalid) {
            if (invalid.getDescription().toLowerCase(java.util.Locale.ROOT).contains("stack overflow"))
                throw new IllegalArgumentException("正则表达式嵌套过深，无法安全编译");
            throw new IllegalArgumentException("正则表达式无效：" + invalid.getDescription());
        }
        catch (StackOverflowError tooDeep) { throw new IllegalArgumentException("正则表达式嵌套过深，无法安全编译"); }
    }

    public static Result find(String expression, String value, long judgementDeadline) {
        long now = System.nanoTime();
        if (now >= judgementDeadline) return new Result(false, "本次断言正则总预算已超过 " + JUDGEMENT_LIMIT_MS + "ms");
        long matchDeadline = Math.min(judgementDeadline, now + TimeUnit.MILLISECONDS.toNanos(MATCH_LIMIT_MS));
        Budget budget = new Budget(matchDeadline, judgementDeadline);
        try {
            Pattern pattern = compile(expression);
            budget.checkNow();
            boolean matched = pattern.matcher(new DeadlineCharSequence(value, 0, value.length(), budget)).find();
            budget.checkNow();
            return new Result(matched, null);
        } catch (LimitExceeded expired) {
            return new Result(false, expired.getMessage());
        } catch (IllegalArgumentException invalid) {
            return new Result(false, invalid.getMessage());
        } catch (StackOverflowError tooDeep) {
            return new Result(false, "正则匹配嵌套过深，已停止该条断言");
        }
    }

    /** 同一次匹配与所有子序列共用；每128次字符读取至少检查一次，length与结束点立即检查。 */
    static final class Budget {
        final long matchDeadline;
        final long judgementDeadline;
        private int reads;
        Budget(long matchDeadline, long judgementDeadline) {
            this.matchDeadline = matchDeadline; this.judgementDeadline = judgementDeadline;
        }
        void checkRead() { if ((++reads & 127) == 0) checkNow(); }
        void checkNow() {
            long now = System.nanoTime();
            if (now >= judgementDeadline) throw new LimitExceeded("本次断言正则总预算已超过 " + JUDGEMENT_LIMIT_MS + "ms");
            if (now >= matchDeadline) throw new LimitExceeded("正则匹配超过 " + MATCH_LIMIT_MS + "ms，已停止该条断言");
        }
    }

    static final class DeadlineCharSequence implements CharSequence {
        private final String value;
        private final int start, end;
        private final Budget budget;
        DeadlineCharSequence(String value, int start, int end, Budget budget) {
            this.value = value; this.start = start; this.end = end; this.budget = budget;
        }
        @Override public int length() { budget.checkNow(); return end - start; }
        @Override public char charAt(int index) {
            budget.checkRead();
            if (index < 0 || index >= end - start) throw new IndexOutOfBoundsException(index);
            return value.charAt(start + index);
        }
        @Override public CharSequence subSequence(int from, int to) {
            budget.checkNow();
            if (from < 0 || to < from || to > end - start) throw new IndexOutOfBoundsException();
            return new DeadlineCharSequence(value, start + from, start + to, budget);
        }
        @Override public String toString() { budget.checkNow(); return value.substring(start, end); }
    }

    private static final class LimitExceeded extends RuntimeException {
        LimitExceeded(String message) { super(message, null, false, false); }
    }
}
