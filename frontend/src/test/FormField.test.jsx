import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FormField from '../components/ui/FormField';

describe('FormField', () => {
  it('renders a text input by default', () => {
    render(<FormField label="Email" />);
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
    expect(screen.getByLabelText('Email').tagName).toBe('INPUT');
    expect(screen.getByLabelText('Email')).toHaveAttribute('type', 'text');
  });

  it('renders a textarea when type="textarea"', () => {
    render(<FormField label="Notes" type="textarea" />);
    expect(screen.getByLabelText('Notes').tagName).toBe('TEXTAREA');
  });

  it('renders a select when type="select"', () => {
    render(
      <FormField label="Category" type="select">
        <option value="a">A</option>
        <option value="b">B</option>
      </FormField>
    );
    // When children are provided, they are rendered instead of the default select
    expect(screen.getByText('A')).toBeInTheDocument();
  });

  it('shows error message when error prop is provided', () => {
    render(<FormField label="Password" error="Too short" />);
    expect(screen.getByText('Too short')).toBeInTheDocument();
  });

  it('does not show error when error prop is absent', () => {
    const { container } = render(<FormField label="Name" />);
    expect(container.querySelector('span')).toBeNull();
  });

  it('sets aria-invalid when error is present', () => {
    render(<FormField label="Field" error="Required" />);
    expect(screen.getByLabelText('Field')).toHaveAttribute('aria-invalid', 'true');
  });

  it('applies custom className', () => {
    const { container } = render(<FormField label="Test" className="custom" />);
    expect(container.firstChild).toHaveClass('input-group', 'custom');
  });

  it('generates id from label', () => {
    render(<FormField label="First Name" />);
    const input = screen.getByLabelText('First Name');
    expect(input).toHaveAttribute('id', 'first-name');
  });

  it('uses explicit id over generated one', () => {
    render(<FormField label="Email" id="custom-email" />);
    expect(screen.getByLabelText('Email')).toHaveAttribute('id', 'custom-email');
  });

  it('forwards ref to input', () => {
    const ref = { current: null };
    render(<FormField label="Ref Test" ref={ref} />);
    expect(ref.current).toBeInstanceOf(HTMLInputElement);
  });

  it('passes through additional props', async () => {
    const onChange = vi.fn();
    render(<FormField label="Input" placeholder="Type here" onChange={onChange} />);
    const input = screen.getByPlaceholderText('Type here');
    const user = userEvent.setup();
    await user.type(input, 'hello');
    expect(onChange).toHaveBeenCalled();
  });
});
