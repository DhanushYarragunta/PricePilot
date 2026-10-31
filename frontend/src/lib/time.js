/**
 * Time formatting.
 *
 * Every timestamp from the backend is an ISO-8601 instant in UTC (Jackson's rendering of
 * `java.time.Instant`, e.g. `2026-10-12T04:31:07.412Z`). The browser knows the viewer's zone, so
 * all conversion happens here and nothing in the UI ever shows a UTC time to a user in IST.
 *
 * ── Why relative times, mostly ──
 * The question a price tracker has to answer constantly is "how current is this?", and "11 minutes
 * ago" answers it directly while "09:52" requires the reader to know what time it is now. Absolute
 * times are still used where the exact moment is the point — the check log, where two attempts
 * seconds apart need distinguishing — and every relative string carries the absolute one in a
 * `title` so hovering reveals it.
 */

const ABSOLUTE = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hour12: true,
});

const TIME_ONLY = new Intl.DateTimeFormat('en-IN', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

const DAY_ONLY = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
});

const DAY_AND_YEAR = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
});

function parse(value) {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** Full date and time in the viewer's zone. Used for `title` attributes and footers. */
export function formatAbsolute(value) {
  const date = parse(value);
  return date ? ABSOLUTE.format(date) : '—';
}

/** Clock time to the second, for the check log where ordering within a minute matters. */
export function formatClock(value) {
  const date = parse(value);
  return date ? TIME_ONLY.format(date) : '—';
}

/** A date without a time, for chart axes and "tracking since". */
export function formatDay(value, { withYear = false } = {}) {
  const date = parse(value);
  if (!date) {
    return '—';
  }
  return withYear ? DAY_AND_YEAR.format(date) : DAY_ONLY.format(date);
}

/**
 * "11 minutes ago", "3 hours ago", "yesterday".
 *
 * Switches to an absolute date past a week, because "17 days ago" is harder to place than
 * "25 Sept". The thresholds are chosen so the string is never ambiguous: there is no "1 day ago",
 * which reads as either yesterday or 23 hours ago depending on the reader.
 */
export function formatRelative(value) {
  const date = parse(value);
  if (!date) {
    return 'never';
  }
  const seconds = Math.round((Date.now() - date.getTime()) / 1000);

  // A clock skew between the browser and the server can put a just-written timestamp a few seconds
  // in the future. "in 4 seconds" for a price that was read during this very request would look
  // like a bug, so anything within a minute either way is "just now".
  if (seconds < 60 && seconds > -60) {
    return 'just now';
  }
  if (seconds < 0) {
    return formatIn(-seconds);
  }
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return minutes + (minutes === 1 ? ' minute ago' : ' minutes ago');
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return hours + (hours === 1 ? ' hour ago' : ' hours ago');
  }
  const days = Math.round(hours / 24);
  if (days === 1) {
    return 'yesterday';
  }
  if (days <= 7) {
    return days + ' days ago';
  }
  return 'on ' + formatDay(value, { withYear: !isThisYear(date) });
}

/**
 * "in 4 hours" — for `nextCheckAt`, which is always in the future.
 *
 * Its own function rather than a negative branch of the above, because the two are read in
 * different places for different reasons and a single function that silently flips tense is easy to
 * call by accident.
 */
export function formatUntil(value) {
  const date = parse(value);
  if (!date) {
    return 'not scheduled';
  }
  const seconds = Math.round((date.getTime() - Date.now()) / 1000);
  if (seconds <= 30) {
    return 'any moment';
  }
  return formatIn(seconds);
}

function formatIn(seconds) {
  const minutes = Math.round(seconds / 60);
  if (minutes < 1) {
    return 'in under a minute';
  }
  if (minutes < 60) {
    return 'in ' + minutes + (minutes === 1 ? ' minute' : ' minutes');
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return 'in ' + hours + (hours === 1 ? ' hour' : ' hours');
  }
  const days = Math.round(hours / 24);
  return 'in ' + days + (days === 1 ? ' day' : ' days');
}

/**
 * An interval in minutes, as words. "every 4 hours", "every 90 minutes".
 *
 * The scheduler stores intervals in minutes and they range from 15 to about 1440, so rendering the
 * raw number would give "every 1380 minutes" at the top end. Rounding to a half-hour above two
 * hours is deliberate: the exact figure is an implementation detail of the backoff curve, and
 * "every 23 hours" is the useful part.
 */
export function formatInterval(minutes) {
  const n = Number(minutes);
  if (!Number.isFinite(n) || n <= 0) {
    return 'not scheduled';
  }
  if (n < 120) {
    return 'every ' + Math.round(n) + (Math.round(n) === 1 ? ' minute' : ' minutes');
  }
  const hours = n / 60;
  if (hours < 24) {
    const rounded = Math.round(hours * 2) / 2;
    return 'every ' + rounded + (rounded === 1 ? ' hour' : ' hours');
  }
  const days = Math.round((hours / 24) * 10) / 10;
  return 'every ' + days + (days === 1 ? ' day' : ' days');
}

/** Milliseconds as a short duration, for scrape latency. */
export function formatLatency(ms) {
  const n = Number(ms);
  if (!Number.isFinite(n) || n < 0) {
    return '—';
  }
  if (n < 1000) {
    return Math.round(n) + 'ms';
  }
  return (n / 1000).toFixed(n < 10000 ? 1 : 0) + 's';
}

function isThisYear(date) {
  return date.getFullYear() === new Date().getFullYear();
}

/** A calendar deadline is not a UTC instant; preserve its day in every browser timezone. */
export function formatDateOnly(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return '—';
  const date = new Date(value + 'T00:00:00Z');
  if (Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value) return '—';
  return new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }).format(date);
}
