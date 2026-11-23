use std::collections::VecDeque;
use std::time::{Duration, Instant};

/// Sliding-window limiter. The mod renders everything it receives, so a spammy
/// channel must not be able to flood the game's chat or HUD.
pub struct RateLimiter {
    max: usize,
    window: Duration,
    hits: VecDeque<Instant>,
}

impl RateLimiter {
    pub fn new(max: usize, window: Duration) -> Self {
        Self {
            max,
            window,
            hits: VecDeque::with_capacity(max),
        }
    }

    pub fn allow(&mut self) -> bool {
        let now = Instant::now();
        while let Some(oldest) = self.hits.front() {
            if now.duration_since(*oldest) > self.window {
                self.hits.pop_front();
            } else {
                break;
            }
        }

        if self.hits.len() >= self.max {
            return false;
        }

        self.hits.push_back(now);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_past_the_limit() {
        let mut limiter = RateLimiter::new(2, Duration::from_secs(5));
        assert!(limiter.allow());
        assert!(limiter.allow());
        assert!(!limiter.allow());
    }
}
