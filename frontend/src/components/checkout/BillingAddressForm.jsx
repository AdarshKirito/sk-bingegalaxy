import React from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Reusable billing-address form block. Lifts state via `value` + `onChange`
 * (controlled component) so the parent can decide whether to persist on
 * submit, on blur or on every keystroke.
 *
 * The shape matches `BillingAddressDto` on the backend:
 *   { fullName, email, phone, companyName, taxId, customerType,
 *     addressLine1, addressLine2, city, stateCode, postalCode, countryCode }
 *
 * Required fields are enforced on submit by the parent — this component
 * only owns the layout.
 */
export default function BillingAddressForm({ value = {}, onChange, disabled = false, compact = false }) {
  const { t } = useTranslation();

  const update = (field) => (e) => {
    const next = { ...value, [field]: e.target.value };
    onChange?.(next);
  };

  const labelCls = 'block text-sm font-medium text-gray-700 mb-1';
  const inputCls = 'w-full px-3 py-2 border border-gray-300 rounded-md text-sm ' +
    'focus:outline-none focus:ring-2 focus:ring-purple-500 disabled:bg-gray-100';

  return (
    <div className={compact ? 'space-y-3' : 'space-y-4'}>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        <div>
          <label className={labelCls}>{t('checkout.billing.fullName', 'Full name')}</label>
          <input className={inputCls} value={value.fullName || ''}
                 onChange={update('fullName')} disabled={disabled} autoComplete="name" />
        </div>
        <div>
          <label className={labelCls}>{t('checkout.billing.customerType', 'Customer type')}</label>
          <select className={inputCls} value={value.customerType || 'B2C'}
                  onChange={update('customerType')} disabled={disabled}>
            <option value="B2C">{t('checkout.billing.b2c', 'Individual')}</option>
            <option value="B2B">{t('checkout.billing.b2b', 'Business')}</option>
          </select>
        </div>
      </div>

      {value.customerType === 'B2B' && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div>
            <label className={labelCls}>{t('checkout.billing.companyName', 'Company name')}</label>
            <input className={inputCls} value={value.companyName || ''}
                   onChange={update('companyName')} disabled={disabled} autoComplete="organization" />
          </div>
          <div>
            <label className={labelCls}>{t('checkout.billing.taxId', 'Tax ID / GSTIN / VAT')}</label>
            <input className={inputCls} value={value.taxId || ''}
                   onChange={update('taxId')} disabled={disabled} />
          </div>
        </div>
      )}

      <div>
        <label className={labelCls}>{t('checkout.billing.addressLine1', 'Address line 1')}</label>
        <input className={inputCls} value={value.addressLine1 || ''}
               onChange={update('addressLine1')} disabled={disabled} autoComplete="address-line1" />
      </div>
      <div>
        <label className={labelCls}>{t('checkout.billing.addressLine2', 'Address line 2 (optional)')}</label>
        <input className={inputCls} value={value.addressLine2 || ''}
               onChange={update('addressLine2')} disabled={disabled} autoComplete="address-line2" />
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        <div>
          <label className={labelCls}>{t('checkout.billing.city', 'City')}</label>
          <input className={inputCls} value={value.city || ''}
                 onChange={update('city')} disabled={disabled} autoComplete="address-level2" />
        </div>
        <div>
          <label className={labelCls}>{t('checkout.billing.state', 'State / Region')}</label>
          <input className={inputCls} value={value.stateCode || ''}
                 onChange={update('stateCode')} disabled={disabled} autoComplete="address-level1"
                 placeholder="e.g. KA, NY, BY" />
        </div>
        <div>
          <label className={labelCls}>{t('checkout.billing.postalCode', 'Postal / ZIP')}</label>
          <input className={inputCls} value={value.postalCode || ''}
                 onChange={update('postalCode')} disabled={disabled} autoComplete="postal-code" />
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        <div>
          <label className={labelCls}>{t('checkout.billing.country', 'Country')}</label>
          <input className={inputCls} value={value.countryCode || ''}
                 onChange={update('countryCode')} disabled={disabled} autoComplete="country"
                 maxLength={2} placeholder="ISO-2 (IN, US, GB, …)" />
        </div>
        <div>
          <label className={labelCls}>{t('checkout.billing.email', 'Receipt email')}</label>
          <input className={inputCls} type="email" value={value.email || ''}
                 onChange={update('email')} disabled={disabled} autoComplete="email" />
        </div>
      </div>
    </div>
  );
}
