import { Helmet } from 'react-helmet-async';

const DEFAULTS = {
  siteName: 'SK Binge Galaxy',
  description: 'Book private theater experiences for your celebrations. Choose events, dates, and add-ons seamlessly.',
};

export default function SEO({ title, description }) {
  const fullTitle = title ? `${title} | ${DEFAULTS.siteName}` : `${DEFAULTS.siteName} - Private Theater Booking`;
  const desc = description || DEFAULTS.description;

  return (
    <Helmet>
      <title>{fullTitle}</title>
      <meta name="description" content={desc} />
      <meta property="og:title" content={fullTitle} />
      <meta property="og:description" content={desc} />
      <meta property="og:type" content="website" />
    </Helmet>
  );
}
