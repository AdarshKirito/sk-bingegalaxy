import './Skeleton.css';

export function SkeletonLine({ width = '100%', height = '1rem', style }) {
  return <div className="skeleton-line" style={{ width, height, ...style }} />;
}

export function SkeletonCard({ lines = 3, hasImage = false }) {
  return (
    <div className="skeleton-card">
      {hasImage && <div className="skeleton-image" />}
      <div className="skeleton-body">
        <SkeletonLine width="60%" height="1.2rem" />
        {Array.from({ length: lines - 1 }).map((_, i) => (
          <SkeletonLine key={i} width={`${80 - i * 15}%`} />
        ))}
      </div>
    </div>
  );
}

export function SkeletonStatCard() {
  return (
    <div className="skeleton-card skeleton-stat">
      <div className="skeleton-circle" />
      <div className="skeleton-body">
        <SkeletonLine width="50%" height="1.5rem" />
        <SkeletonLine width="70%" height="0.8rem" />
      </div>
    </div>
  );
}

export function SkeletonGrid({ count = 4, columns = 2, hasImage = false }) {
  return (
    <div className={`grid-${columns}`}>
      {Array.from({ length: count }).map((_, i) => (
        <SkeletonCard key={i} hasImage={hasImage} />
      ))}
    </div>
  );
}
