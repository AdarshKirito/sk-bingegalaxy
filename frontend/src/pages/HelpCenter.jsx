import { useEffect, useState } from 'react';
import { Link, useParams, Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useBinge } from '../context/BingeContext';
import { authService } from '../services/endpoints';
import { useAccountPageContent, spliceHours } from '../hooks/useAccountPageContent';
import {
  buildSupportEmailHref,
  buildSupportWhatsAppHref,
  getCallSupportHref,
  mergeSupportContact,
} from '../services/customerExperience';
import SEO from '../components/SEO';
import { SkeletonGrid } from '../components/ui/Skeleton';
import {
  FiArrowLeft, FiArrowRight, FiClock, FiCreditCard, FiGift, FiHelpCircle,
  FiLifeBuoy, FiList, FiMail, FiMessageCircle, FiPhoneCall, FiShield,
} from 'react-icons/fi';
import './CustomerHub.css';

const TOPICS = {
  'how-it-works': {
    title: 'How it works',
    kicker: 'Your experience, step by step',
    description: 'Everything between picking an experience and walking in with your booking reference.',
    icon: <FiList />,
  },
  faq: {
    title: 'Frequently asked questions',
    kicker: 'Help before you need it',
    description: 'The questions customers ask most, answered without the back-and-forth.',
    icon: <FiHelpCircle />,
  },
  benefits: {
    title: 'Benefits & offers',
    kicker: 'Reasons to keep coming back',
    description: 'Member benefits, referral offers, and pricing perks on your account.',
    icon: <FiGift />,
  },
  support: {
    title: 'Support & policy',
    kicker: 'We are reachable before anything goes wrong',
    description: 'Contact channels, support hours, and the policies behind cancellations and payment help.',
    icon: <FiLifeBuoy />,
  },
};

/**
 * Dedicated full pages for the customer help panels ("More" links on the
 * Dashboard and Account Center). Content is CMS-driven per binge: what the
 * venue admin (or super-admin globally) authored in the Account Page Editor
 * is exactly what renders here.
 */
export default function HelpCenter() {
  const { topic } = useParams();
  const { user } = useAuth();
  const { selectedBinge } = useBinge();
  const { content, loading } = useAccountPageContent(selectedBinge?.id);
  const [supportContact, setSupportContact] = useState(null);

  useEffect(() => {
    if (topic !== 'support') return;
    authService.getSupportContact()
      .then((r) => setSupportContact(r.data?.data || null))
      .catch(() => setSupportContact(null));
  }, [topic]);

  const meta = TOPICS[topic];
  if (!meta) return <Navigate to="/help/faq" replace />;

  const customerName = `${user?.firstName || ''} ${user?.lastName || ''}`.trim();
  const support = mergeSupportContact(supportContact, selectedBinge);
  const hours = content.supportHours;

  return (
    <div className="container customer-hub">
      <SEO title={meta.title} description={meta.description} />

      <section className="customer-hub-hero">
        <div className="customer-hub-copy">
          <span className="customer-hub-kicker">{meta.kicker}</span>
          <h1>{meta.title}</h1>
          <p>
            {meta.description}
            {selectedBinge ? ` This page reflects ${selectedBinge.name}.` : ''}
          </p>
          <div className="customer-hub-actions">
            <Link to="/account" className="btn btn-secondary"><FiArrowLeft /> Back to Account</Link>
            {Object.entries(TOPICS).filter(([slug]) => slug !== topic).map(([slug, t]) => (
              <Link key={slug} to={`/help/${slug}`} className="btn btn-secondary">{t.icon} {t.title}</Link>
            ))}
          </div>
        </div>
      </section>

      {loading ? (
        <SkeletonGrid count={4} columns={2} />
      ) : (
        <>
          {topic === 'faq' && (
            <section className="customer-hub-panel card">
              <div className="customer-hub-panel-head">
                <div>
                  <span className="customer-hub-panel-label">Frequently asked</span>
                  <h2>All questions and answers</h2>
                </div>
              </div>
              <div className="customer-faq-list">
                {content.faqs.map((item) => (
                  <article key={item.question} className="customer-faq-item">
                    <h3>{item.question}</h3>
                    <p>{item.answer}</p>
                  </article>
                ))}
              </div>
              <div className="customer-hub-inline-actions">
                <span className="customer-account-note">Still stuck?</span>
                <Link to="/messages" className="btn btn-primary btn-sm"><FiMessageCircle /> Message {selectedBinge ? selectedBinge.name : 'Support'}</Link>
              </div>
            </section>
          )}

          {topic === 'how-it-works' && (
            <section className="customer-hub-panel card">
              <div className="customer-hub-panel-head">
                <div>
                  <span className="customer-hub-panel-label">How it works</span>
                  <h2>From idea to private screening</h2>
                </div>
              </div>
              <ol className="customer-steps-list">
                {content.howItWorksSteps.map((step) => (
                  <li key={step}>{step}</li>
                ))}
              </ol>
              <div className="customer-hub-inline-actions">
                <Link to={selectedBinge ? '/book' : '/binges'} className="btn btn-primary btn-sm">
                  {selectedBinge ? 'Start a booking' : 'Browse venues'} <FiArrowRight />
                </Link>
              </div>
            </section>
          )}

          {topic === 'benefits' && (
            <section className="customer-hub-panel card">
              <div className="customer-hub-panel-head">
                <div>
                  <span className="customer-hub-panel-label">Benefits and retention</span>
                  <h2>Every member benefit in one place</h2>
                </div>
              </div>
              <div className="customer-benefits-grid">
                {content.memberOffers.map((offer) => (
                  <article key={offer.title} className="customer-benefit-card">
                    <span className="customer-hub-panel-label">{offer.title}</span>
                    <h3>{offer.title}</h3>
                    <p>{offer.description}</p>
                  </article>
                ))}
              </div>
              <div className="customer-hub-inline-actions">
                <Link to="/membership" className="btn btn-primary btn-sm"><FiGift /> Loyalty & membership</Link>
                <Link to="/account" className="btn btn-secondary btn-sm">Account preferences</Link>
              </div>
            </section>
          )}

          {topic === 'support' && (
            <section className="customer-account-grid">
              <article className="customer-hub-panel card customer-account-card">
                <div className="customer-hub-panel-head">
                  <div>
                    <span className="customer-hub-panel-label">Contact</span>
                    <h2>{selectedBinge ? `Reach ${selectedBinge.name}` : 'Reach platform support'}</h2>
                  </div>
                  <span className="customer-account-avatar"><FiLifeBuoy /></span>
                </div>
                <div className="customer-account-support-grid">
                  <Link to="/messages" className="customer-account-support-link">
                    <FiMessageCircle />
                    <div>
                      <strong>In-app message</strong>
                      <span>{selectedBinge ? `Goes straight to the ${selectedBinge.name} team` : 'Goes to platform Support'}</span>
                    </div>
                  </Link>
                  {support.email && (
                    <a href={buildSupportEmailHref({ supportContact: support, customerName, topic: 'Customer support' })} className="customer-account-support-link">
                      <FiMail />
                      <div>
                        <strong>Email support</strong>
                        <span>{support.email}</span>
                      </div>
                    </a>
                  )}
                  {support.whatsappRaw && (
                    <a href={buildSupportWhatsAppHref({ supportContact: support, customerName, topic: 'support' })} target="_blank" rel="noreferrer" className="customer-account-support-link">
                      <FiMessageCircle />
                      <div>
                        <strong>WhatsApp</strong>
                        <span>Fastest path for booking changes and payment help</span>
                      </div>
                    </a>
                  )}
                  {support.phoneRaw && (
                    <a href={getCallSupportHref(support)} className="customer-account-support-link">
                      <FiPhoneCall />
                      <div>
                        <strong>Call support</strong>
                        <span>{support.phoneDisplay || support.phoneRaw}</span>
                      </div>
                    </a>
                  )}
                </div>
                {!support.email && !support.whatsappRaw && !support.phoneRaw && (
                  <p className="customer-account-note">
                    {selectedBinge
                      ? 'This venue has not published direct contact channels — use the in-app message above and the team will reply in your inbox.'
                      : 'Use the in-app message above and the team will reply in your inbox.'}
                  </p>
                )}
              </article>

              <article className="customer-hub-panel card customer-account-card">
                <div className="customer-hub-panel-head">
                  <div>
                    <span className="customer-hub-panel-label">Policy</span>
                    <h2>{content.helpAndTrustHeading}</h2>
                  </div>
                  <span className="customer-account-avatar"><FiShield /></span>
                </div>
                <div className="customer-account-policy-list">
                  <p><FiShield /> {content.cancellationPolicy}</p>
                  <p><FiCreditCard /> {content.paymentHelpPolicy}</p>
                  <p><FiClock /> Support window: {hours}</p>
                  {content.helpAndTrustPoints.map((point) => (
                    <p key={point}><FiShield /> {spliceHours(point, hours)}</p>
                  ))}
                </div>
              </article>
            </section>
          )}
        </>
      )}
    </div>
  );
}
