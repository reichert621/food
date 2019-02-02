import React from 'react';
import ReactDOM from 'react-dom';
import startOfDay from 'date-fns/start_of_day';
import format from 'date-fns/format';
import groupBy from 'lodash/groupBy';
import map from 'lodash/map';
import './App.less';
import { BrowserRouter as Router, Switch, Route } from 'react-router-dom';

const Attachment = ({ attachment }) => {
  if (!attachment || !attachment.type || !attachment.payload) {
    return null;
  }
  const { type, payload } = attachment;
  const uri = payload['firebase-uri'];

  switch (type) {
    case 'image':
      return <img src={uri} />;
    case 'video':
      return (
        <video controls>
          <source src={uri} />
        </video>
      );
    case 'audio':
      return (
        <audio controls>
          <source src={uri} />
        </audio>
      );
  }
};

const Event = ({ event }) => {
  if (!event) return null;

  const {
    message: { text, attachments = [] },
    timestamp
  } = event;

  return (
    <li className="entry">
      <div className="entry-time">{format(timestamp, 'h:mma')}</div>
      <div className="entry-content">
        {text ? (
          <div className="entry-text">{text}</div>
        ) : (
          <div className="entry-attachment-container">
            <Attachment attachment={attachments[0]} />
          </div>
        )}
      </div>
    </li>
  );
};

function Header() {
  return <h1 className="header">👋 pluot</h1>;
}

class Log extends React.Component {
  constructor(props) {
    super(props);
    this.state = { entriesByDate: null };
  }

  componentDidMount() {
    const userHash = this.props.match.params.hash;
    window
      .fetch(`https://hipluot.herokuapp.com/users/${userHash}`)
      .then(res => res.json())
      .then(rawEntries => {
        const parsedEntries = Object.values(rawEntries);
        const entriesByDate = map(
          groupBy(parsedEntries, e => startOfDay(e.timestamp).getTime()),
          (v, k) => ({
            date: parseInt(k),
            entries: v.sort((x, y) => x.timestamp - y.timestamp)
          })
        ).sort((x, y) => y.date - x.date);

        this.setState({ entriesByDate });
      })
      .catch(err => {
        console.log('Error!', err);
        this.setState({ data: {} });
      });
  }

  render() {
    const { entriesByDate } = this.state;

    if (!entriesByDate) {
      return <div>Loading...</div>;
    }

    if (Object.keys(entriesByDate).length === 0) {
      return <div>Log something!</div>;
    }

    return (
      <div className="log">
        <Header />
        {entriesByDate.map(({ date, entries }) => (
          <div key={date} className="day">
            <h3 className="day-date">{format(date, 'ddd, MMM D')}</h3>
            <ul className="entries">
              {entries.map(event => (
                <Event key={event.timestamp} event={event} />
              ))}
            </ul>
          </div>
        ))}
      </div>
    );
  }
}

function Home() {
  return (
    <div className="home">
      <div>
        <Header />
        <div className="intro">
          <a className="start-button" href="sms:+1-650-758-0158">
            Start logging 👉
          </a>
          <a
            className="apple-shortcut-link"
            href="https://www.icloud.com/shortcuts/d9d55f5046f749299df782b85b108c15"
            target="_blank"
          >
            Get the shortcut
          </a>
        </div>
      </div>
    </div>
  );
}

ReactDOM.render(
  <Router>
    <Switch>
      <Route exact path="/" component={Home} />
      <Route path="/u/:hash" component={Log} />
    </Switch>
  </Router>,
  document.getElementById('app')
);
